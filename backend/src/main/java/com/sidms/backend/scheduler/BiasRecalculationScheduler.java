package com.sidms.backend.scheduler;

import com.sidms.backend.entity.BiasHistory;
import com.sidms.backend.entity.NodeTimeseries;
import com.sidms.backend.entity.StationMetadata;
import com.sidms.backend.entity.StationObservation;
import com.sidms.backend.entity.WeatherNode;
import com.sidms.backend.repository.BiasHistoryRepository;
import com.sidms.backend.repository.NodeTimeseriesRepository;
import com.sidms.backend.repository.StationMetadataRepository;
import com.sidms.backend.repository.StationObservationRepository;
import com.sidms.backend.repository.WeatherNodeRepository;
import com.sidms.backend.service.SyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Distributed bias correction scheduler.
 *
 * Algorithm:
 *   For each physical station with a recent observation (≤ 4h old):
 *     1. IDW-interpolate the model temperature at the station location
 *        using the 6 nearest active WeatherNodes' forecast_hour=0 temperature.
 *     2. Compute total bias = observed − interpolated.
 *     3. Distribute the bias to each of the 6 nodes weighted by their IDW share.
 *     4. Persist a BiasHistory record per (node, variable).
 *   After all stations:
 *     - Roll up 7-day mean bias into weather_nodes.bias_temp_c (JdbcTemplate bulk UPDATE).
 *     - Prune bias_history records older than 30 days.
 *
 * Runs 30 minutes after the Yr.no sync (00:30, 06:30, 12:30, 18:30) so fresh
 * node_timeseries rows are guaranteed to be in place.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BiasRecalculationScheduler {

    private static final String   JOB_NAME    = "bias_recalc_sync";
    private static final Duration COOLDOWN    = Duration.ofHours(6);

    /** Observation window — only readings from the last 4 hours are used. */
    private static final Duration OBS_WINDOW  = Duration.ofHours(4);
    /** Number of nearest nodes to use in IDW. */
    private static final int      IDW_NODES   = 6;

    private final StationObservationRepository stationObservationRepository;
    private final StationMetadataRepository    stationMetadataRepository;
    private final WeatherNodeRepository        weatherNodeRepository;
    private final NodeTimeseriesRepository     nodeTimeseriesRepository;
    private final BiasHistoryRepository        biasHistoryRepository;
    private final SyncStateService             syncStateService;
    private final JdbcTemplate                 jdbcTemplate;

    // =========================================================================
    // Scheduled entry point
    // =========================================================================

    /** Fires at 00:30, 06:30, 12:30, 18:30 — 30 min after Yr.no syncs. */
    @Scheduled(cron = "0 30 0,6,12,18 * * *")
    public void scheduledRun() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN)) return;
        try {
            doSync();
            syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
        } catch (Exception e) {
            log.error("[{}] Sync failed: {}", JOB_NAME, e.getMessage(), e);
            syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
        }
    }

    // =========================================================================
    // Public sync — callable from AdminSyncController for manual override
    // =========================================================================

    public void doSync() {
        log.info("[BiasRecalc] Sync started");

        List<StationMetadata> stations   = stationMetadataRepository.findAll();
        List<WeatherNode>     allNodes   = weatherNodeRepository.findByIsActiveTrue();
        LocalDateTime         windowFrom = LocalDateTime.now().minus(OBS_WINDOW);

        int processedStations = 0;
        List<BiasHistory>     batchBias  = new ArrayList<>();

        for (StationMetadata station : stations) {
            try {
                // ── Step a: latest observation within 4h window ───────────────
                Optional<StationObservation> obsOpt =
                        stationObservationRepository
                                .findTopByStationIdAndTimestampUtcAfterOrderByTimestampUtcDesc(
                                        station.getStationId(), windowFrom);

                if (obsOpt.isEmpty()) continue;
                StationObservation obs = obsOpt.get();
                if (obs.getTemperatureC() == null) continue;

                double observedTemp = obs.getTemperatureC().doubleValue();

                // ── Step b/c: station coordinates + 6 nearest nodes ──────────
                double stLat = station.getLatitude().doubleValue();
                double stLon = station.getLongitude().doubleValue();
                double stElev = station.getElevationM() != null
                        ? station.getElevationM().doubleValue() : 0.0;

                List<NodeWithDist> nearest = findNearestNodes(allNodes, stLat, stLon, stElev);
                if (nearest.isEmpty()) continue;

                // ── Step d: load forecast_hour=0 temperature per node ─────────
                List<NodeWithTemp> nodesWithTemp = new ArrayList<>();
                for (NodeWithDist nd : nearest) {
                    Optional<NodeTimeseries> tsOpt =
                            nodeTimeseriesRepository
                                    .findTopByNodeIdAndForecastHourOrderByValidFromUtcDesc(
                                            nd.node.getId(), 0);
                    if (tsOpt.isEmpty() || tsOpt.get().getTemperatureC() == null) continue;

                    nodesWithTemp.add(new NodeWithTemp(
                            nd.node,
                            tsOpt.get().getTemperatureC().doubleValue(),
                            nd.effectiveDist));
                }
                if (nodesWithTemp.isEmpty()) continue;

                // ── Step e/f: IDW interpolation ───────────────────────────────
                double totalWeight       = 0.0;
                double weightedTempSum   = 0.0;
                double[] weights         = new double[nodesWithTemp.size()];

                for (int i = 0; i < nodesWithTemp.size(); i++) {
                    NodeWithTemp nwt = nodesWithTemp.get(i);
                    double effDist = nwt.effectiveDist == 0.0 ? 0.001 : nwt.effectiveDist;
                    double w = 1.0 / (effDist * effDist);
                    weights[i]      = w;
                    totalWeight    += w;
                    weightedTempSum += nwt.modelTempC * w;
                }

                double interpolated = weightedTempSum / totalWeight;

                // ── Step g: total bias at station ────────────────────────────
                double totalBias = observedTemp - interpolated;

                // ── Step h: distribute bias to each node ─────────────────────
                for (int i = 0; i < nodesWithTemp.size(); i++) {
                    double share = totalBias * (weights[i] / totalWeight);
                    batchBias.add(BiasHistory.builder()
                            .nodeId(nodesWithTemp.get(i).node.getId())
                            .variable("temperature")
                            .biasValue(BigDecimal.valueOf(share))
                            .timestampUtc(obs.getTimestampUtc())
                            .createdAt(LocalDateTime.now())
                            .build());
                }

                processedStations++;

            } catch (Exception e) {
                log.warn("[BiasRecalc] Failed to process station {}: {}",
                        station.getStationId(), e.getMessage());
            }
        }

        // Persist all bias history records in one batch
        if (!batchBias.isEmpty()) {
            biasHistoryRepository.saveAll(batchBias);
        }

        // ── Step 3: roll up 7-day mean bias into weather_nodes ────────────────
        int updatedNodes = jdbcTemplate.update("""
                UPDATE weather_nodes wn
                SET bias_temp_c = COALESCE((
                    SELECT AVG(bh.bias_value)
                    FROM bias_history bh
                    WHERE bh.node_id = wn.id
                      AND bh.variable = 'temperature'
                      AND bh.timestamp_utc > NOW() - INTERVAL '7 days'
                ), 0)
                WHERE wn.is_active = true
                """);

        // ── Step 4: prune old bias history ────────────────────────────────────
        int pruned = jdbcTemplate.update(
                "DELETE FROM bias_history WHERE timestamp_utc < NOW() - INTERVAL '30 days'");

        if (pruned > 0) {
            log.info("[BiasRecalc] Pruned {} old bias records (> 30 days)", pruned);
        }

        log.info("[BiasRecalc] Processed {} stations, updated bias for {} nodes",
                processedStations, updatedNodes);
    }

    // =========================================================================
    // Private — nearest node selection
    // =========================================================================

    private List<NodeWithDist> findNearestNodes(
            List<WeatherNode> allNodes, double stLat, double stLon, double stElev) {

        List<NodeWithDist> distances = new ArrayList<>(allNodes.size());

        for (WeatherNode node : allNodes) {
            double horizDist = haversineKm(node.getLat(), node.getLng(), stLat, stLon);

            double nodeElev = (node.getElevationM() != null)
                    ? node.getElevationM().doubleValue() : 0.0;
            double vertDist  = Math.abs(nodeElev - stElev) / 100.0;
            double effDist   = Math.sqrt(horizDist * horizDist + vertDist * vertDist);

            distances.add(new NodeWithDist(node, effDist));
        }

        distances.sort(Comparator.comparingDouble(nd -> nd.effectiveDist));
        return distances.subList(0, Math.min(IDW_NODES, distances.size()));
    }

    // =========================================================================
    // Private — haversine distance
    // =========================================================================

    /**
     * Great-circle distance between two lat/lon points in kilometres.
     * Earth radius = 6371 km.
     */
    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // =========================================================================
    // Private — local value holders
    // =========================================================================

    private record NodeWithDist(WeatherNode node, double effectiveDist) {}

    private record NodeWithTemp(WeatherNode node, double modelTempC, double effectiveDist) {}
}
