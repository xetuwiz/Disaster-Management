package com.sidms.backend.scheduler;

import com.sidms.backend.client.OpenMeteoClient;
import com.sidms.backend.config.ApiKeyConfig;
import com.sidms.backend.entity.OpenmeteoForecastEnsemble;
import com.sidms.backend.entity.WeatherNode;
import com.sidms.backend.entity.WeatherNodeLiveCache;
import com.sidms.backend.entity.WeatherNodeTelemetryLog;
import com.sidms.backend.entity.SpatialForecastSnapshot;
import com.sidms.backend.repository.ForecastProjectionRepository;
import com.sidms.backend.repository.OpenmeteoForecastEnsembleRepository;
import com.sidms.backend.repository.SpatialForecastSnapshotRepository;
import com.sidms.backend.repository.SpatialUnitRepository;
import com.sidms.backend.repository.SpatialUnitWeatherNodeMappingRepository;
import com.sidms.backend.repository.WeatherNodeLiveCacheRepository;
import com.sidms.backend.repository.WeatherNodeTelemetryLogRepository;
import com.sidms.backend.repository.WeatherNodeRepository;
import com.sidms.backend.service.AnalyticsService;
import com.sidms.backend.util.ApiKeyManager;
import com.sidms.backend.util.CacheKeys;
import com.sidms.backend.entity.ForecastProjection;
import com.sidms.backend.entity.SpatialUnit;
import com.sidms.backend.entity.SpatialUnitWeatherNodeMapping;
import com.sidms.backend.entity.enums.SpatialType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.sidms.backend.service.SyncStateService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.ArrayList;

@Component
@Slf4j
@RequiredArgsConstructor
public class WeatherSyncScheduler {

    private static final String JOB_NAME_CURRENT = "weather_sync";
    private static final String JOB_NAME_FORECAST = "openmeteo_forecast_sync";
    private static final Duration COOLDOWN_CURRENT = Duration.ofHours(6);
    private static final Duration COOLDOWN_FORECAST = Duration.ofHours(6);

    private final SyncStateService syncStateService;
    private final WeatherNodeRepository weatherNodeRepository;
    private final WeatherNodeLiveCacheRepository liveCacheRepository;
    private final ApiKeyConfig apiKeyConfig;
    private final OpenMeteoClient openMeteoClient;
    private final ApiKeyManager apiKeyManager;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisTemplate<String, String> stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final SpatialUnitRepository spatialUnitRepository;
    private final ForecastProjectionRepository forecastProjectionRepository;
    private final SpatialForecastSnapshotRepository spatialForecastSnapshotRepository;
    private final SpatialUnitWeatherNodeMappingRepository spatialUnitWeatherNodeMappingRepository;
    private final OpenmeteoForecastEnsembleRepository openmeteoForecastEnsembleRepository;
    private final AnalyticsService analyticsService;
    private final WeatherNodeTelemetryLogRepository weatherNodeTelemetryLogRepository;

    // Remove @RequiredArgsConstructor or keep it and ensure all final fields are
    // accounted for
    // If using @RequiredArgsConstructor with final fields, it should work fine now.

    @Value("${app.sync.weather.enabled:true}")
    private boolean weatherSyncEnabled;

    @Value("${app.sync.weather.current-enabled:true}")
    private boolean currentWeatherSyncEnabled;

    @Value("${app.sync.weather.openmeteo-fallback-enabled:false}")
    private boolean openMeteoFallbackEnabled;

    @Value("${app.sync.weather.aqi-enabled:false}")
    private boolean aqiEnabled;

    @Value("${app.sync.weather.aqi-max-calls-per-run:0}")
    private int aqiMaxCallsPerRun;

    @Value("${app.sync.debug.verbose:false}")
    private boolean verboseSyncDebug;

    @Value("${app.sync.weather.forecast-max-units:-1}")
    private int forecastMaxUnits;

    // ──────────────────────────────────────────────
    // Open-Meteo Forecast & AQI Sync (runs 4 times a day)
    // ──────────────────────────────────────────────
    @Scheduled(cron = "0 15 0,6,12,18 * * *") // Every 6 hours
    @Transactional
    public void scheduledSyncWeatherForecasts() {
        if (!syncStateService.shouldRun(JOB_NAME_FORECAST, COOLDOWN_FORECAST))
            return;
        try {
            if (!weatherSyncEnabled) {
                log.info("Skipping forecast sync because app.sync.weather.enabled=false");
                syncStateService.recordSuccess(JOB_NAME_FORECAST, COOLDOWN_FORECAST);
                return;
            }
            syncWeatherForecasts();
            syncAqiData();
            reevaluateVolatileFlags();
            updateAllForecastActuals();
            syncStateService.recordSuccess(JOB_NAME_FORECAST, COOLDOWN_FORECAST);
        } catch (Exception e) {
            log.error("[{}] Sync failed: {}", JOB_NAME_FORECAST, e.getMessage(), e);
            syncStateService.recordFailure(JOB_NAME_FORECAST, COOLDOWN_FORECAST, e.getMessage());
        }
    }

    @Transactional
    public void syncWeatherForecasts() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        log.info("⏳ Scheduled forecast sync started runId={}", runId);
        Set<UUID> syncedUnitIds = new HashSet<>();

        List<SpatialUnit> units = spatialUnitRepository.findByType(SpatialType.GN_DIVISION);
        List<SpatialUnit> targetUnits = units;
        if (forecastMaxUnits > 0 && forecastMaxUnits < units.size()) {
            targetUnits = units.stream().limit(forecastMaxUnits).collect(Collectors.toList());
        }

        log.info("Forecast sync runId={} targetUnits={} totalGnUnits={} maxUnitsSetting={}",
                runId,
                targetUnits.size(),
                units.size(),
                forecastMaxUnits);

        for (int i = 0; i < targetUnits.size(); i += 50) {
            List<SpatialUnit> batch = targetUnits.subList(i, Math.min(i + 50, targetUnits.size()));
            Instant batchStart = Instant.now();
            try {
                if (verboseSyncDebug) {
                    log.info("Forecast sync runId={} batchStart={} batchSize={} firstUnit={}",
                            runId,
                            i,
                            batch.size(),
                            batch.isEmpty() ? "-" : batch.get(0).getName());
                }

                String lats = batch.stream().map(u -> String.valueOf(u.getLat())).collect(Collectors.joining(","));
                String lngs = batch.stream().map(u -> String.valueOf(u.getLng())).collect(Collectors.joining(","));
                String params = "daily=temperature_2m_max,temperature_2m_min,temperature_2m_mean,"
                        + "precipitation_sum,precipitation_probability_max,weather_code"
                        + "&forecast_days=14&timezone=auto";

                JsonNode root = openMeteoClient.getCurrentBatch(lats, lngs, params);
                if (root == null)
                    continue;

                if (root.isArray()) {
                    for (int j = 0; j < root.size() && j < batch.size(); j++) {
                        processUnitForecast(batch.get(j), root.get(j));
                        syncedUnitIds.add(batch.get(j).getId());
                    }
                } else if (!batch.isEmpty()) {
                    processUnitForecast(batch.get(0), root);
                    syncedUnitIds.add(batch.get(0).getId());
                }

                if (verboseSyncDebug) {
                    log.info("Forecast sync runId={} batchDone={} elapsedMs={}",
                            runId,
                            i,
                            Duration.between(batchStart, Instant.now()).toMillis());
                }
            } catch (Exception e) {
                log.error("Forecast batch sync failed runId={} batchStart={} firstUnit={} elapsedMs={} message={}",
                        runId,
                        i,
                        batch.isEmpty() ? "-" : batch.get(0).getName(),
                        Duration.between(batchStart, Instant.now()).toMillis(),
                        e.getMessage());
            }
        }

        evictAnalyticsOverviewCacheForUnits(syncedUnitIds, runId);
        log.info("✅ Scheduled forecast sync completed runId={}", runId);
    }

    private void evictAnalyticsOverviewCacheForUnits(Set<UUID> spatialUnitIds, String runId) {
        if (spatialUnitIds == null || spatialUnitIds.isEmpty()) {
            return;
        }

        try {
            Set<String> keys = spatialUnitIds.stream()
                    .map(id -> "analytics:overview:" + id)
                    .collect(Collectors.toSet());
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                if (verboseSyncDebug) {
                    log.info("Forecast sync runId={} evicted analytics cache keys count={}", runId, keys.size());
                }
            }
        } catch (Exception e) {
            log.warn("Forecast sync runId={} failed to evict analytics overview cache: {}", runId, e.getMessage());
        }
    }

    private void processUnitForecast(SpatialUnit unit, JsonNode data) {
        persistSpatialForecastSnapshot(unit, data);
        cacheSpatialForecastPayload(unit, data);

        if (data != null && data.has("daily")) {
            JsonNode daily = data.get("daily");
            JsonNode times = daily.get("time");
            JsonNode precip = daily.get("precipitation_sum");

            if (times != null && precip != null && times.isArray() && precip.isArray()) {
                List<Double> predictions = new ArrayList<>();
                for (int i = 1; i < Math.min(precip.size(), 8); i++) { // Next 7 days
                    predictions.add(precip.get(i).asDouble());
                }

                // Create projection entry
                ForecastProjection projection = ForecastProjection.builder()
                        .spatialUnitId(unit.getId())
                        .forecastDate(java.time.LocalDate.now(ZoneOffset.UTC))
                        .metric("precipitation")
                        .pointEstimate(predictions.isEmpty() ? 0.0 : predictions.get(0))
                        .generatedAt(LocalDateTime.now(ZoneOffset.UTC))
                        .horizonDays(predictions.size())
                        .build();

                projection = forecastProjectionRepository.save(projection);
                analyticsService.saveForecastsForComparison(unit.getId(), projection.getId(), "precipitation",
                        predictions, null, null);
            }

            // ── Ensemble percentiles — days 8-14 only (days 0-6 are reliable ECMWF) ──
            if (times != null && times.isArray() && daily.has("temperature_2m_max_p50")) {
                // Resolve the primary (rank=1) node for this spatial unit
                List<SpatialUnitWeatherNodeMapping> mappings = spatialUnitWeatherNodeMappingRepository
                        .findBySpatialUnitIdOrderByRankAsc(unit.getId());
                UUID primaryNodeId = mappings.isEmpty() ? null : mappings.get(0).getWeatherNodeId();

                if (primaryNodeId != null) {
                    for (int i = 7; i < Math.min(times.size(), 14); i++) {
                        try {
                            LocalDate forecastDate = LocalDate.parse(times.get(i).asText());

                            // Upsert: load existing row if present, otherwise create new
                            OpenmeteoForecastEnsemble ens = openmeteoForecastEnsembleRepository
                                    .findByNodeIdAndForecastDate(primaryNodeId, forecastDate)
                                    .orElseGet(() -> OpenmeteoForecastEnsemble.builder()
                                            .nodeId(primaryNodeId)
                                            .forecastDate(forecastDate)
                                            .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                                            .build());

                            // Fallback: Use standard min/max as P50 since we dropped the ensemble endpoint call to save API limits.
                            Double minTemp = getDoubleAt(daily, "temperature_2m_min", i);
                            Double maxTemp = getDoubleAt(daily, "temperature_2m_max", i);
                            Double rainProb = getDoubleAt(daily, "precipitation_probability_max", i);

                            if (minTemp != null) {
                                ens.setTempMinP50(BigDecimal.valueOf(minTemp));
                            }
                            if (maxTemp != null) {
                                ens.setTempMaxP50(BigDecimal.valueOf(maxTemp));
                            }
                            if (rainProb != null) {
                                ens.setPrecipitationProbability(BigDecimal.valueOf(rainProb));
                            }

                            openmeteoForecastEnsembleRepository.save(ens);
                        } catch (Exception e) {
                            log.warn("[WeatherSyncScheduler] Failed saving ensemble for unit={} day={}: {}",
                                    unit.getId(), i, e.getMessage());
                        }
                    }
                }
            }
        }
    }

    private void persistSpatialForecastSnapshot(SpatialUnit unit, JsonNode data) {
        if (unit == null || data == null || data.isNull()) {
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            SpatialForecastSnapshot snapshot = spatialForecastSnapshotRepository
                    .findBySpatialUnitId(unit.getId())
                    .orElseGet(() -> SpatialForecastSnapshot.builder()
                            .spatialUnitId(unit.getId())
                            .build());

            snapshot.setSourceApi("open-meteo");
            snapshot.setPayload(data.toString());
            snapshot.setGeneratedAt(now);
            snapshot.setUpdatedAt(now);
            spatialForecastSnapshotRepository.save(snapshot);
        } catch (Exception e) {
            log.warn("Failed persisting forecast snapshot for unit={} reason={}", unit.getId(), e.getMessage());
        }
    }

    private void cacheSpatialForecastPayload(SpatialUnit unit, JsonNode data) {
        if (unit == null || data == null || data.isNull()) {
            return;
        }

        try {
            String key = CacheKeys.weatherForecastSpatial(unit.getId().toString());
            stringRedisTemplate.opsForValue().set(key, data.toString(), CacheKeys.TTL_FORECAST_SHORT);
        } catch (Exception e) {
            if (verboseSyncDebug) {
                log.warn("Failed caching forecast payload for unit={} reason={}", unit.getId(), e.getMessage());
            }
        }
    }

    // ──────────────────────────────────────────────
    // AQI Sync
    // ──────────────────────────────────────────────
    private void syncAqiData() {
        if (!aqiEnabled) return;
        List<WeatherNode> activeNodes = weatherNodeRepository.findByIsActiveTrue();
        if (activeNodes.isEmpty()) return;

        log.info("⏳ AQI sync started for {} nodes", activeNodes.size());
        int calls = 0;
        int budget = Math.max(0, aqiMaxCallsPerRun);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        for (WeatherNode node : activeNodes) {
            if (budget <= 0) break;
            try {
                JsonNode aqiData = openMeteoClient.getAirQualityCurrent(node.getLat(), node.getLng());
                if (aqiData != null) {
                    updateAqiData(node.getId(), aqiData, now);
                    calls++;
                }
                budget--;
            } catch (Exception e) {
                log.debug("AQI fetch skipped for node {}: {}", node.getCode(), e.getMessage());
            }
        }
        log.info("✅ AQI sync completed, {} calls made", calls);
    }

    private void updateAqiData(UUID nodeId, JsonNode aqiData, LocalDateTime now) {
        JsonNode current = aqiData.path("current");
        if (current.isMissingNode())
            return;

        Double aqi = getDouble(current, "us_aqi");
        Double pm10 = getDouble(current, "pm10");
        Double pm25 = getDouble(current, "pm2_5");

        if (aqi != null || pm10 != null || pm25 != null) {
            jdbcTemplate.update(
                    "UPDATE weather_node_live_cache SET us_aqi = COALESCE(?, us_aqi), " +
                            "pm10 = COALESCE(?, pm10), pm2_5 = COALESCE(?, pm2_5), updated_at = ? " +
                            "WHERE weather_node_id = ?",
                    aqi, pm10, pm25, now, nodeId);
        }
    }

    private void updateAllForecastActuals() {
        log.info("Updating forecast actuals for all tracked spatial units");
        List<SpatialUnit> units = spatialUnitRepository.findByType(SpatialType.GN_DIVISION);
        for (SpatialUnit unit : units) {
            try {
                // Get historical trend (last 30 days) to find finalized actuals
                List<com.sidms.backend.dto.analytics.DailyWeatherDto> trend = analyticsService
                        .computeHistoricalTrend(unit.getId());
                for (com.sidms.backend.dto.analytics.DailyWeatherDto day : trend) {
                    // Only update for past dates that are likely finalized
                    if (day.getDate().isBefore(java.time.LocalDate.now(ZoneOffset.UTC))) {
                        if (day.getPrecipMm() != null) {
                            analyticsService.updateForecastWithActuals(unit.getId(), "precipitation", day.getDate(),
                                    day.getPrecipMm());
                        }
                        if (day.getTempMean() != null) {
                            analyticsService.updateForecastWithActuals(unit.getId(), "temperature", day.getDate(),
                                    day.getTempMean());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Skip actuals update for unit {}: {}", unit.getName(), e.getMessage());
            }
        }
    }


    // ──────────────────────────────────────────────
    // Re-evaluate volatile flag
    // ──────────────────────────────────────────────
    private void reevaluateVolatileFlags() {
        // Reset all to non-volatile first
        jdbcTemplate.update("UPDATE weather_nodes SET is_volatile = false WHERE is_active = true");

        // Mark nodes with heavy precipitation or high CAPE as volatile
        jdbcTemplate.update(
                "UPDATE weather_nodes wn SET is_volatile = true " +
                        "FROM weather_node_live_cache wlc " +
                        "WHERE wn.id = wlc.weather_node_id " +
                        "AND wn.is_active = true " +
                        "AND (wlc.precipitation_mm > 20 OR wlc.cape_jkg > 1000)");

        log.info("Volatile flag re-evaluation complete");
    }

    // ──────────────────────────────────────────────
    // Evict Redis weather caches (offset by 5s)
    // ──────────────────────────────────────────────
    @Scheduled(fixedDelayString = "${app.sync.weather.interval}", initialDelayString = "${app.sync.cache-evict.initial-delay}")
    public void evictWeatherCaches() {
        if (!weatherSyncEnabled || !currentWeatherSyncEnabled) {
            log.debug("Skipping weather cache eviction because weather/current sync is disabled");
            return;
        }

        log.info("Evicting weather:spatial:* Redis caches");
        try {
            Set<String> keys = stringRedisTemplate.keys("weather:spatial:*");
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                log.info("Evicted {} weather cache keys", keys.size());
            }
        } catch (Exception e) {
            log.warn("Redis cache eviction failed: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────
    private void logApiUsage(String provider, String endpoint, int callCount, long responseTimeMs) {
        for (int i = 0; i < callCount; i++) {
            jdbcTemplate.update(
                    "INSERT INTO api_usage_logs (provider, endpoint, status_code, response_time_ms) VALUES (?, ?, ?, ?)",
                    provider, endpoint, 200, (int) responseTimeMs);
        }
    }

    private Double getDouble(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }

    private Integer getInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private Double multiplyOrNull(Double value, double factor) {
        return value == null ? null : value * factor;
    }

    /**
     * Safely extracts a Double from {@code daily.get(field).get(index)}.
     * Returns null if the field is missing, the index is out of bounds, or the
     * value is null/NaN.
     */
    private Double getDoubleAt(JsonNode daily, String field, int index) {
        JsonNode arr = daily.path(field);
        if (arr.isMissingNode() || !arr.isArray() || index >= arr.size())
            return null;
        JsonNode val = arr.get(index);
        return (val == null || val.isNull()) ? null : val.asDouble();
    }

    private static class SyncStats {
        int openMeteoBatchCalls;
        int aqiCalls;
        int staleNodesRetained;
    }
}
