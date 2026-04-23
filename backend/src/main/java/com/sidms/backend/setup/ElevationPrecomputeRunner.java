package com.sidms.backend.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidms.backend.entity.SpatialUnit;
import com.sidms.backend.entity.WeatherNode;
import com.sidms.backend.entity.enums.SpatialType;
import com.sidms.backend.repository.SpatialUnitRepository;
import com.sidms.backend.repository.WeatherNodeRepository;
import com.sidms.backend.service.SyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;


/**
 * Runs ONCE at startup (after Flyway) to populate elevation data for all
 * weather nodes and GN division spatial units using the OpenTopoData API
 * (srtm90m dataset, ~90 m resolution, global coverage).
 *
 * <p>Target columns:
 * <ul>
 *   <li>weather_nodes.elevation_m  (Integer)</li>
 *   <li>spatial_units.elevation_m  (Double, GN_DIVISION only)</li>
 * </ul>
 *
 * <p>Idempotency: checks DB state directly (NULL elevation_m = not yet
 * fetched). A node with elevation_m = 0 is a valid sea-level reading and
 * is NOT re-fetched on subsequent startups.
 *
 * <p>A failure here is non-fatal — the application continues to start, and
 * IDW lapse-rate corrections fall back to 0 m if elevation is missing.
 *
 * <p>OpenTopoData public API limits:
 * <ul>
 *   <li>Max 100 locations per request</li>
 *   <li>Max 1 request per second</li>
 *   <li>Max 1,000 requests per day</li>
 * </ul>
 * With 100 coords/batch: 677 nodes = 7 requests (~8 s), 14 043 units = 141
 * requests (~2.6 min). Total quota used: ~148 of 1 000 daily requests.
 */
@Component
@Order(100)
@Slf4j
@RequiredArgsConstructor
public class ElevationPrecomputeRunner implements ApplicationRunner {

    // ── Constants ──────────────────────────────────────────────────────────────

    private static final String JOB_NAME           = "elevation_populated";
    /** OpenTopoData SRTM-90m endpoint — global coverage, no API key needed. */
    private static final String API_BASE           = "https://api.opentopodata.org/v1/srtm90m?locations=";
    private static final int    BATCH_SIZE         = 100;  // max per request
    private static final long   BATCH_SLEEP_MS     = 1_100L; // 1.1 s → ≤ 1 req/s
    private static final long   RATE_LIMIT_WAIT_MS = 5_000L; // wait 5 s on 429, then retry once

    // ── Dependencies ───────────────────────────────────────────────────────────

    private final WeatherNodeRepository weatherNodeRepository;
    private final SpatialUnitRepository spatialUnitRepository;
    private final SyncStateService      syncStateService;
    private final RestTemplate          restTemplate;
    private final ObjectMapper          objectMapper;

    // ── Entry point ────────────────────────────────────────────────────────────

    @Override
    public void run(ApplicationArguments args) {
        try {
            // ── Work-based idempotency ─────────────────────────────────────────
            // Only elevation_m IS NULL means "not yet fetched".
            // elevation_m = 0 is a valid sea-level reading — do NOT re-fetch it.
            List<WeatherNode> allNodes      = weatherNodeRepository.findByIsActiveTrue();
            List<WeatherNode> nodesPending  = allNodes.stream()
                    .filter(n -> n.getElevationM() == null)
                    .toList();

            List<SpatialUnit> gnDivisions   = spatialUnitRepository.findByType(SpatialType.GN_DIVISION);
            List<SpatialUnit> unitsPending  = gnDivisions.stream()
                    .filter(u -> u.getElevationM() == null)
                    .toList();

            // Skip when ≥ 95 % of both phases are already populated
            boolean nodesOk = allNodes.isEmpty()    || nodesPending.size() < (allNodes.size()    * 0.05);
            boolean unitsOk = gnDivisions.isEmpty() || unitsPending.size() < (gnDivisions.size() * 0.05);

            if (nodesOk && unitsOk) {
                log.info("[ElevationPrecomputeRunner] ✓ Elevation ≥95% populated " +
                                "(nodes pending: {}, units pending: {}) — skipping.",
                        nodesPending.size(), unitsPending.size());
                syncStateService.recordSuccess(JOB_NAME, Duration.ofDays(9999));
                return;
            }

            log.info("[ElevationPrecomputeRunner] Starting elevation pre-computation " +
                            "(nodes pending: {}/{}, units pending: {}/{})...",
                    nodesPending.size(), allNodes.size(),
                    unitsPending.size(), gnDivisions.size());

            // ── Phase 1: Weather nodes ─────────────────────────────────────────
            int nodeUpdates = 0;
            if (nodesPending.isEmpty()) {
                log.info("[ElevationPrecomputeRunner] Phase 1: all weather nodes already populated.");
            } else {
                log.info("[ElevationPrecomputeRunner] Phase 1: {} nodes → {} batches of {}",
                        nodesPending.size(),
                        (nodesPending.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                        BATCH_SIZE);
                nodeUpdates = populateNodeElevations(nodesPending);
            }

            // ── Phase 2: GN Division spatial units ────────────────────────────
            int unitUpdates = 0;
            if (unitsPending.isEmpty()) {
                log.info("[ElevationPrecomputeRunner] Phase 2: all GN divisions already populated.");
            } else {
                log.info("[ElevationPrecomputeRunner] Phase 2: {} units → {} batches of {}",
                        unitsPending.size(),
                        (unitsPending.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                        BATCH_SIZE);
                unitUpdates = populateUnitElevations(unitsPending);
            }

            // ── Mark complete only when ≥ 50 % of pending work was written ────
            boolean phase1Ok = nodesPending.isEmpty() || nodeUpdates >= (nodesPending.size() * 0.50);
            boolean phase2Ok = unitsPending.isEmpty() || unitUpdates >= (unitsPending.size() * 0.50);

            if (phase1Ok && phase2Ok) {
                syncStateService.recordSuccess(JOB_NAME, Duration.ofDays(9999));
                log.info("[ElevationPrecomputeRunner] ✓ Complete — {} nodes, {} units populated.",
                        nodeUpdates, unitUpdates);
            } else {
                log.warn("[ElevationPrecomputeRunner] Partial ({}/{} nodes, {}/{} units) — " +
                                "will retry remaining on next startup.",
                        nodeUpdates, nodesPending.size(),
                        unitUpdates, unitsPending.size());
            }

        } catch (Exception e) {
            log.error("[ElevationPrecomputeRunner] Failed — app still starts. Reason: {}",
                    e.getMessage(), e);
        }
    }

    // ── Phase 1: Weather nodes ─────────────────────────────────────────────────

    private int populateNodeElevations(List<WeatherNode> nodes) {
        int total    = nodes.size();
        int batchNum = 0;
        int updated  = 0;

        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<WeatherNode> batch = nodes.subList(i, Math.min(i + BATCH_SIZE, total));
            batchNum++;

            try {
                String locParam = buildLocationParam(
                        batch.stream().map(WeatherNode::getLat).toList(),
                        batch.stream().map(WeatherNode::getLng).toList());

                List<Double> elevations = fetchElevations(locParam, batch.size());
                if (elevations == null) {
                    log.warn("[ElevationPrecomputeRunner] Node batch {} — null response, skipping.", batchNum);
                    continue;
                }

                for (int j = 0; j < batch.size(); j++) {
                    Double elev = elevations.get(j);
                    if (elev != null) {
                        batch.get(j).setElevationM(elev.intValue());
                        updated++;
                    }
                }
                weatherNodeRepository.saveAll(batch);

                if (batchNum % 5 == 0 || batchNum == (total + BATCH_SIZE - 1) / BATCH_SIZE) {
                    log.info("[ElevationPrecomputeRunner] Node progress: {}/{} batches — {} updated",
                            batchNum, (total + BATCH_SIZE - 1) / BATCH_SIZE, updated);
                }

                sleepPolitely();

            } catch (Exception e) {
                log.warn("[ElevationPrecomputeRunner] Node batch {} failed: {}", batchNum, e.getMessage());
            }
        }

        log.info("[ElevationPrecomputeRunner] Phase 1 done: {}/{} nodes populated.", updated, total);
        return updated;
    }

    // ── Phase 2: GN Division spatial units ────────────────────────────────────

    private int populateUnitElevations(List<SpatialUnit> units) {
        int total    = units.size();
        int batchNum = 0;
        int updated  = 0;

        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<SpatialUnit> batch = units.subList(i, Math.min(i + BATCH_SIZE, total));
            batchNum++;

            try {
                // Skip units without coordinates
                List<SpatialUnit> geocoded = batch.stream()
                        .filter(u -> u.getLat() != null && u.getLng() != null)
                        .toList();
                if (geocoded.isEmpty()) continue;

                String locParam = buildLocationParam(
                        geocoded.stream().map(SpatialUnit::getLat).toList(),
                        geocoded.stream().map(SpatialUnit::getLng).toList());

                List<Double> elevations = fetchElevations(locParam, geocoded.size());
                if (elevations == null) {
                    log.warn("[ElevationPrecomputeRunner] Unit batch {} — null response, skipping.", batchNum);
                    continue;
                }

                for (int j = 0; j < geocoded.size(); j++) {
                    Double elev = elevations.get(j);
                    if (elev != null) {
                        geocoded.get(j).setElevationM(elev);
                        updated++;
                    }
                }
                spatialUnitRepository.saveAll(geocoded);

                if (batchNum % 25 == 0 || batchNum == (total + BATCH_SIZE - 1) / BATCH_SIZE) {
                    log.info("[ElevationPrecomputeRunner] Unit progress: {}/{} batches — {} updated",
                            batchNum, (total + BATCH_SIZE - 1) / BATCH_SIZE, updated);
                }

                sleepPolitely();

            } catch (Exception e) {
                log.warn("[ElevationPrecomputeRunner] Unit batch {} failed: {}", batchNum, e.getMessage());
            }
        }

        log.info("[ElevationPrecomputeRunner] Phase 2 done: {}/{} units populated.", updated, total);
        return updated;
    }

    // ── OpenTopoData API call ──────────────────────────────────────────────────

    /**
     * Calls the OpenTopoData srtm90m endpoint and returns elevations in the
     * same order as the input coordinates.
     * <p>
     * URL format: {@code .../srtm90m?locations=lat,lng|lat,lng|...}
     * <p>
     * Response: {@code {"results":[{"elevation":100.0,...}],"status":"OK"}}
     *
     * @return list of elevation values (same size as {@code expectedCount}),
     *         or {@code null} if the request failed after retry.
     */
    private List<Double> fetchElevations(String locParam, int expectedCount) {
        String url = API_BASE + locParam;

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String json = restTemplate.getForObject(url, String.class);
                if (json == null || json.isBlank()) return null;

                JsonNode root = objectMapper.readTree(json);

                // API-level error field
                if (root.path("error").asBoolean(false)) {
                    log.warn("[ElevationPrecomputeRunner] API error: {}", root.path("reason").asText());
                    return null;
                }

                // status != OK
                String status = root.path("status").asText("");
                if (!"OK".equals(status)) {
                    log.warn("[ElevationPrecomputeRunner] Unexpected status '{}': {}", status, json);
                    return null;
                }

                JsonNode results = root.path("results");
                if (results.isMissingNode() || !results.isArray() || results.size() != expectedCount) {
                    log.warn("[ElevationPrecomputeRunner] Result count mismatch: got {}, expected {}",
                            results.size(), expectedCount);
                    return null;
                }

                List<Double> elevations = new ArrayList<>(results.size());
                for (JsonNode r : results) {
                    JsonNode elev = r.path("elevation");
                    elevations.add(elev.isNull() || elev.isMissingNode() ? 0.0 : elev.asDouble());
                }
                return elevations;

            } catch (HttpStatusCodeException e) {
                if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS && attempt == 1) {
                    log.warn("[ElevationPrecomputeRunner] 429 — waiting {}s before retry",
                            RATE_LIMIT_WAIT_MS / 1000);
                    sleepMs(RATE_LIMIT_WAIT_MS);
                } else {
                    log.warn("[ElevationPrecomputeRunner] HTTP {} on attempt {}: {}",
                            e.getStatusCode(), attempt, e.getMessage());
                    return null;
                }
            } catch (Exception e) {
                log.warn("[ElevationPrecomputeRunner] API call failed (attempt {}): {}", attempt, e.getMessage());
                return null;
            }
        }
        return null;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds the OpenTopoData {@code locations} parameter.
     * Format: {@code lat,lng|lat,lng|...}
     */
    private String buildLocationParam(List<Double> lats, List<Double> lons) {
        StringJoiner sj = new StringJoiner("|");
        for (int i = 0; i < lats.size(); i++) {
            sj.add(lats.get(i) + "," + lons.get(i));
        }
        return sj.toString();
    }

    private void sleepPolitely() {
        sleepMs(BATCH_SLEEP_MS);
    }

    private void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
