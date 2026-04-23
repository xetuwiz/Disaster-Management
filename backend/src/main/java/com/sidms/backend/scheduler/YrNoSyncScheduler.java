package com.sidms.backend.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.sidms.backend.client.YrNoClient;
import com.sidms.backend.client.YrNoClient.NodeTimeseriesData;
import com.sidms.backend.entity.NodeTimeseries;
import com.sidms.backend.entity.WeatherNode;
import com.sidms.backend.entity.WeatherNodeLiveCache;
import com.sidms.backend.repository.NodeTimeseriesRepository;
import com.sidms.backend.repository.WeatherNodeLiveCacheRepository;
import com.sidms.backend.repository.WeatherNodeTelemetryLogRepository;
import com.sidms.backend.entity.WeatherNodeTelemetryLog;
import com.sidms.backend.repository.WeatherNodeRepository;
import com.sidms.backend.service.SyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Syncs Yr.no (ECMWF) 0–72h hourly forecasts for every active WeatherNode.
 *
 * Runs at midnight, 06:00, 12:00, 18:00 (4 times/day).
 * Yr.no enforces 1 req/sec — this scheduler sleeps 1100ms between nodes.
 * After all nodes are synced, volatile flags are re-evaluated from
 * precipitation data.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class YrNoSyncScheduler {

    private static final String JOB_NAME = "yrno_sync";
    private static final Duration COOLDOWN = Duration.ofHours(6);

    private final YrNoClient yrNoClient;
    private final WeatherNodeRepository weatherNodeRepository;
    private final NodeTimeseriesRepository nodeTimeseriesRepository;
    private final WeatherNodeLiveCacheRepository weatherNodeLiveCacheRepository;
    private final WeatherNodeTelemetryLogRepository weatherNodeTelemetryLogRepository;
    private final SyncStateService syncStateService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    // =========================================================================
    // Scheduled entry point
    // =========================================================================

    /** Fires at 00:00, 06:00, 12:00, 18:00 every day. */
    @Scheduled(cron = "0 0 0,6,12,18 * * *")
    public void scheduledRun() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN))
            return;
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
        List<WeatherNode> nodes = weatherNodeRepository.findByIsActiveTrue();
        int total = nodes.size();
        log.info("[YrNo] Sync started — {} nodes to process", total);

        long startMs = System.currentTimeMillis();
        int synced = 0;

        for (int i = 0; i < nodes.size(); i++) {
            WeatherNode node = nodes.get(i);

            try {
                // 1. Fetch forecast from Yr.no
                JsonNode data = yrNoClient.fetchNode(node.getLat(), node.getLng());

                // 2. Parse timeseries (up to 72 hourly entries)
                List<NodeTimeseriesData> series = yrNoClient.parseTimeseries(data, node.getId());

                if (!series.isEmpty()) {
                    transactionTemplate.execute(status -> {
                        // 3. Replace old timeseries for this node
                        nodeTimeseriesRepository.deleteByNodeId(node.getId());
                        nodeTimeseriesRepository.saveAll(toEntities(series));

                        // 4. Upsert live cache with the current-hour (forecastHour=0) entry
                        updateLiveCache(node.getId(), series.get(0), data.toString());
                        return null;
                    });
                    synced++;
                } else {
                    log.warn("[YrNo] No timeseries data returned for node {} ({}, {})",
                            node.getId(), node.getLat(), node.getLng());
                }

                // 5. Progress logging every 100 nodes
                if ((i + 1) % 100 == 0) {
                    log.info("[YrNo] Progress: {}/{}", i + 1, total);
                }

            } catch (Exception e) {
                log.error("[YrNo] Failed to sync node {} lat={} lon={}: {}",
                        node.getId(), node.getLat(), node.getLng(), e.getMessage(), e);
                // Continue to next node — one node failure must not abort the full sync
            }

            // 6. Rate-limit guard: Yr.no enforces 1 req/sec; sleep slightly over 1000ms
            sleepRateLimit();
        }

        // 7. Re-evaluate volatile flags from fresh precipitation data
        refreshVolatileFlags();

        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("[YrNo] Sync complete — {} nodes synced in {}ms", synced, elapsedMs);
    }

    // =========================================================================
    // Private — live cache upsert
    // =========================================================================

    /**
     * Updates (or inserts) the WeatherNodeLiveCache row for the given node.
     * Uses the forecast_hour=0 entry from the fresh Yr.no timeseries.
     * This keeps backward compatibility for WeatherService until it is fully
     * migrated
     * to read from node_timeseries directly.
     */
    private void updateLiveCache(UUID nodeId, NodeTimeseriesData current, String rawPayload) {
        Optional<WeatherNodeLiveCache> existing = weatherNodeLiveCacheRepository.findById(nodeId);

        WeatherNodeLiveCache cache = existing.orElseGet(() -> WeatherNodeLiveCache.builder()
                .weatherNodeId(nodeId)
                .build());

        cache.setSourceApi("yr-no");
        cache.setTempC(current.getTemperatureC());
        cache.setHumidityPct(current.getHumidityPct());
        cache.setWindSpeedKmh(current.getWindSpeedMs() != null
                ? current.getWindSpeedMs() * 3.6 // m/s → km/h
                : null);
        cache.setPrecipitationMm(current.getPrecipitationMm());
        cache.setPressureHpa(current.getPressureHpa());
        cache.setWindDirectionDeg(current.getWindDirectionDeg() != null
                ? current.getWindDirectionDeg().doubleValue()
                : null);
        
        // Map newly added comprehensive fields where applicable to LiveCache
        cache.setCloudCoverPct(current.getCloudCoverPct());
        cache.setWindGustKmh(current.getWindGustMs() != null ? current.getWindGustMs() * 3.6 : null);
        cache.setUvIndex(current.getUvIndexClearSky());
        cache.setPrecipProbability(current.getPrecipProbPct());
        cache.setSymbolCode(current.getSymbolCode());
        
        cache.setRawPayload(rawPayload);
        cache.setFetchedAt(current.getValidFromUtc());
        cache.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        weatherNodeLiveCacheRepository.save(cache);

        // Insert telemetry log
        WeatherNodeTelemetryLog telemetry = WeatherNodeTelemetryLog.builder()
                .weatherNodeId(nodeId)
                .loggedAt(LocalDateTime.now(ZoneOffset.UTC))
                .sourceApi("yr-no")
                .tempC(cache.getTempC())
                .precipitationMm(cache.getPrecipitationMm())
                .precipProbability(cache.getPrecipProbability())
                .rainMm(cache.getRainMm())
                .humidityPct(cache.getHumidityPct())
                .windSpeedKmh(cache.getWindSpeedKmh())
                .cloudCoverPct(cache.getCloudCoverPct())
                .capeJkg(cache.getCapeJkg())
                .uvIndex(cache.getUvIndex())
                .build();

        weatherNodeTelemetryLogRepository.save(telemetry);
    }

    // =========================================================================
    // Private — volatile flag refresh
    // =========================================================================

    /**
     * Re-evaluates weather_nodes.is_volatile for all active nodes.
     *
     * Step 1: Reset all active nodes to is_volatile = false.
     * Step 2: Flag as volatile any node whose live cache shows precipitation_mm >
     * 20.
     *
     * Using JdbcTemplate (bulk SQL) to avoid loading 14 000+ entities into the JPA
     * context.
     */
    private void refreshVolatileFlags() {
        // Reset all active nodes
        int reset = jdbcTemplate.update(
                "UPDATE weather_nodes SET is_volatile = false WHERE is_active = true");

        // Flag nodes with significant precipitation
        int flagged = jdbcTemplate.update("""
                UPDATE weather_nodes wn
                SET is_volatile = true
                FROM weather_node_live_cache wlc
                WHERE wn.id = wlc.weather_node_id
                  AND wn.is_active = true
                  AND wlc.precipitation_mm > 20
                """);

        log.info("[YrNo] Volatile flags refreshed — reset: {}, flagged volatile: {}", reset, flagged);
    }

    // =========================================================================
    // Private — entity conversion
    // =========================================================================

    private List<NodeTimeseries> toEntities(List<NodeTimeseriesData> series) {
        List<NodeTimeseries> entities = new ArrayList<>(series.size());
        for (NodeTimeseriesData d : series) {
            entities.add(NodeTimeseries.builder()
                    .nodeId(d.getNodeId())
                    .validFromUtc(d.getValidFromUtc())
                    .forecastHour(d.getForecastHour())
                    .temperatureC(d.getTemperatureC() != null
                            ? new java.math.BigDecimal(d.getTemperatureC().toString())
                            : null)
                    .humidityPct(d.getHumidityPct() != null
                            ? new java.math.BigDecimal(d.getHumidityPct().toString())
                            : null)
                    .windSpeedMs(d.getWindSpeedMs() != null
                            ? new java.math.BigDecimal(d.getWindSpeedMs().toString())
                            : null)
                    .windDirectionDeg(d.getWindDirectionDeg())
                    .pressureHpa(d.getPressureHpa() != null
                            ? new java.math.BigDecimal(d.getPressureHpa().toString())
                            : null)
                    .precipitationMm(d.getPrecipitationMm() != null
                            ? new java.math.BigDecimal(d.getPrecipitationMm().toString())
                            : null)
                    .cloudCoverPct(d.getCloudCoverPct() != null ? new java.math.BigDecimal(d.getCloudCoverPct().toString()) : null)
                    .cloudCoverHighPct(d.getCloudCoverHighPct() != null ? new java.math.BigDecimal(d.getCloudCoverHighPct().toString()) : null)
                    .cloudCoverLowPct(d.getCloudCoverLowPct() != null ? new java.math.BigDecimal(d.getCloudCoverLowPct().toString()) : null)
                    .cloudCoverMediumPct(d.getCloudCoverMediumPct() != null ? new java.math.BigDecimal(d.getCloudCoverMediumPct().toString()) : null)
                    .dewPointC(d.getDewPointC() != null ? new java.math.BigDecimal(d.getDewPointC().toString()) : null)
                    .fogAreaFractionPct(d.getFogAreaFractionPct() != null ? new java.math.BigDecimal(d.getFogAreaFractionPct().toString()) : null)
                    .uvIndexClearSky(d.getUvIndexClearSky() != null ? new java.math.BigDecimal(d.getUvIndexClearSky().toString()) : null)
                    .windGustMs(d.getWindGustMs() != null ? new java.math.BigDecimal(d.getWindGustMs().toString()) : null)
                    .tempPercentile10(d.getTempPercentile10() != null ? new java.math.BigDecimal(d.getTempPercentile10().toString()) : null)
                    .tempPercentile90(d.getTempPercentile90() != null ? new java.math.BigDecimal(d.getTempPercentile90().toString()) : null)
                    .windPercentile10(d.getWindPercentile10() != null ? new java.math.BigDecimal(d.getWindPercentile10().toString()) : null)
                    .windPercentile90(d.getWindPercentile90() != null ? new java.math.BigDecimal(d.getWindPercentile90().toString()) : null)
                    .precipProbPct(d.getPrecipProbPct() != null ? new java.math.BigDecimal(d.getPrecipProbPct().toString()) : null)
                    .thunderProbPct(d.getThunderProbPct() != null ? new java.math.BigDecimal(d.getThunderProbPct().toString()) : null)
                    .precipMinMm(d.getPrecipMinMm() != null ? new java.math.BigDecimal(d.getPrecipMinMm().toString()) : null)
                    .precipMaxMm(d.getPrecipMaxMm() != null ? new java.math.BigDecimal(d.getPrecipMaxMm().toString()) : null)
                    .tempMaxC(d.getTempMaxC() != null ? new java.math.BigDecimal(d.getTempMaxC().toString()) : null)
                    .tempMinC(d.getTempMinC() != null ? new java.math.BigDecimal(d.getTempMinC().toString()) : null)
                    .weatherCode(d.getWeatherCode())
                    .symbolCode(d.getSymbolCode())
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build());
        }
        return entities;
    }

    // =========================================================================
    // Private — rate limit sleep
    // =========================================================================

    /** Sleeps 1100ms between Yr.no requests — safely over the 1 req/sec limit. */
    private void sleepRateLimit() {
        try {
            Thread.sleep(1_100L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[YrNo] Rate-limit sleep interrupted");
        }
    }
}
