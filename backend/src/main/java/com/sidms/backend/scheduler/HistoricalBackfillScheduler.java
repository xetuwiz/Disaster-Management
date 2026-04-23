package com.sidms.backend.scheduler;

import com.sidms.backend.config.ApiKeyConfig;
import com.sidms.backend.entity.WeatherNode;
import com.sidms.backend.entity.WeatherNodeHistoricalDaily;
import com.sidms.backend.repository.WeatherNodeHistoricalDailyRepository;
import com.sidms.backend.repository.WeatherNodeRepository;
import com.sidms.backend.service.SyncStateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.sidms.backend.client.OpenMeteoClient;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class HistoricalBackfillScheduler {

    private static final String   JOB_NAME = "historical_backfill";
    private static final Duration COOLDOWN  = Duration.ofHours(20);

    private final SyncStateService                       syncStateService;
    private final WeatherNodeRepository                  weatherNodeRepository;
    private final WeatherNodeHistoricalDailyRepository   historicalDailyRepository;
    private final ApiKeyConfig                           apiKeyConfig;
    private final OpenMeteoClient                        openMeteoClient;
    private final JdbcTemplate                          jdbcTemplate;

    @Value("${app.sync.debug.verbose:false}")
    private boolean verboseSyncDebug;

    // ──────────────────────────────────────────────
    // Daily backfill at 2:00 AM
    // ──────────────────────────────────────────────
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void scheduledBackfill() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN)) return;
        try {
            backfillHistory();
            syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
        } catch (Exception e) {
            log.error("[{}] Sync failed: {}", JOB_NAME, e.getMessage(), e);
            syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
        }
    }

    /**
     * Public entry point for daily backfill — also called by AdminSyncController.
     * Processes yesterday for all active nodes.
     */
    @Transactional
    public void backfillHistory() {
        backfillHistoricalData();
    }

    @Transactional
    public void backfillHistoricalData() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        log.info("⏳ Historical backfill started runId={}", runId);
        long start = System.currentTimeMillis();

        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        List<WeatherNode> activeNodes = weatherNodeRepository.findByIsActiveTrue();

        int aggregated = 0;
        int fetched = 0;
        int skipped = 0;

        for (WeatherNode node : activeNodes) {
            try {
                // Check if yesterday's data already exists
                boolean exists = historicalDailyRepository
                        .findByWeatherNodeIdAndDateBetween(node.getId(), yesterday, yesterday)
                        .stream()
                        .anyMatch(h -> h.getDate().equals(yesterday));

                if (exists) {
                    skipped++;
                    continue;
                }

                // Priority 1: aggregate from telemetry logs
                boolean filledFromTelemetry = aggregateFromTelemetry(node, yesterday);
                if (filledFromTelemetry) {
                    aggregated++;
                    continue;
                }

                // Priority 2: fetch from Open-Meteo archive API
                boolean filledFromArchive = fetchFromArchive(node, yesterday);
                if (filledFromArchive) {
                    fetched++;
                } else {
                    log.warn("No data available for node {} on {}", node.getCode(), yesterday);
                }
            } catch (Exception e) {
                log.error("Backfill failed runId={} for node {}: {}", runId, node.getCode(), e.getMessage());
            }

            if (verboseSyncDebug && (aggregated + fetched + skipped) % 100 == 0) {
                log.info("Historical backfill progress runId={} processed={} aggregated={} fetched={} skipped={}",
                        runId,
                        aggregated + fetched + skipped,
                        aggregated,
                        fetched,
                        skipped);
            }
        }

        // Log API usage for archive calls
        if (fetched > 0) {
            logApiUsage("open-meteo-archive", "https://archive-api.open-meteo.com/v1/archive", fetched,
                    System.currentTimeMillis() - start);
        }

        log.info("✅ Historical backfill completed runId={} in {}ms – {} aggregated, {} fetched, {} skipped",
                runId, System.currentTimeMillis() - start, aggregated, fetched, skipped);
    }

    // ──────────────────────────────────────────────
    // Priority 1: Aggregate from telemetry log
    // ──────────────────────────────────────────────
    private boolean aggregateFromTelemetry(WeatherNode node, LocalDate date) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT " +
                        "  COUNT(*) AS data_points, " +
                        "  MAX(temp_c) AS temp_max, " +
                        "  MIN(temp_c) AS temp_min, " +
                        "  AVG(temp_c) AS temp_mean, " +
                        "  SUM(precipitation_mm) AS precip_sum, " +
                        "  AVG(humidity_pct) AS humidity_mean, " +
                        "  MAX(wind_speed_kmh) AS wind_max, " +
                        "  AVG(cloud_cover_pct) AS cloud_mean, " +
                        "  MAX(cape_jkg) AS cape_max " +
                        "FROM weather_node_telemetry_log " +
                        "WHERE weather_node_id = ? " +
                        "AND logged_at::date = ?",
                node.getId(), date);

        if (results.isEmpty())
            return false;

        Map<String, Object> row = results.get(0);
        long dataPoints = ((Number) row.getOrDefault("data_points", 0L)).longValue();

        // Need at least 10 data points for a reliable daily aggregate (~every 3 hours
        // min)
        if (dataPoints < 10)
            return false;

        WeatherNodeHistoricalDaily daily = WeatherNodeHistoricalDaily.builder()
                .weatherNodeId(node.getId())
                .date(date)
                .sourceName("telemetry-aggregate")
                .sourcePriority(1)
                .tempMaxC(toDouble(row.get("temp_max")))
                .tempMinC(toDouble(row.get("temp_min")))
                .tempMeanC(toDouble(row.get("temp_mean")))
                .precipSumMm(toDouble(row.get("precip_sum")))
                .humidityMeanPct(toDouble(row.get("humidity_mean")))
                .windMaxKmh(toDouble(row.get("wind_max")))
                .cloudMeanPct(toDouble(row.get("cloud_mean")))
                .capeMax(toDouble(row.get("cape_max")))
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        historicalDailyRepository.save(daily);
        return true;
    }

    // ──────────────────────────────────────────────
    // Priority 2: Fetch from Open-Meteo archive
    // ──────────────────────────────────────────────
    private boolean fetchFromArchive(WeatherNode node, LocalDate date) {
        Instant start = Instant.now();
        String dailyParams = "daily=temperature_2m_max,temperature_2m_min,temperature_2m_mean," +
                "precipitation_sum,precipitation_hours,relative_humidity_2m_mean," +
                "wind_speed_10m_max,wind_direction_10m_dominant,cloud_cover_mean," +
                "uv_index_max,et0_fao_evapotranspiration";

        try {
            JsonNode root = openMeteoClient.getArchiveDaily(
                    node.getLat(), node.getLng(), date.toString(), date.toString(), dailyParams);

            if (root == null) {
                log.warn("Open-Meteo archive returned null for node {} date={} elapsedMs={}",
                        node.getCode(),
                        date,
                        Duration.between(start, Instant.now()).toMillis());
                return false;
            }

            JsonNode daily = root.path("daily");
            if (daily.isMissingNode()) {
                log.warn("Open-Meteo archive missing daily payload for node {} date={} elapsedMs={}",
                        node.getCode(),
                        date,
                        Duration.between(start, Instant.now()).toMillis());
                return false;
            }

            WeatherNodeHistoricalDaily record = WeatherNodeHistoricalDaily.builder()
                    .weatherNodeId(node.getId())
                    .date(date)
                    .sourceName("open-meteo-archive")
                    .sourcePriority(2)
                    .tempMaxC(getDoubleFromArray(daily, "temperature_2m_max", 0))
                    .tempMinC(getDoubleFromArray(daily, "temperature_2m_min", 0))
                    .tempMeanC(getDoubleFromArray(daily, "temperature_2m_mean", 0))
                    .precipSumMm(getDoubleFromArray(daily, "precipitation_sum", 0))
                    .precipHours(getDoubleFromArray(daily, "precipitation_hours", 0))
                    .humidityMeanPct(getDoubleFromArray(daily, "relative_humidity_2m_mean", 0))
                    .windMaxKmh(getDoubleFromArray(daily, "wind_speed_10m_max", 0))
                    .cloudMeanPct(getDoubleFromArray(daily, "cloud_cover_mean", 0))
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .build();

            historicalDailyRepository.save(record);
            if (verboseSyncDebug) {
                log.info("Archive backfill saved node={} date={} tempMean={} precipSum={} elapsedMs={}",
                        node.getCode(),
                        date,
                        record.getTempMeanC(),
                        record.getPrecipSumMm(),
                        Duration.between(start, Instant.now()).toMillis());
            }
            return true;
        } catch (Exception e) {
            log.error("Open-Meteo archive fetch failed for node {}: {}", node.getCode(), e.getMessage());
            return false;
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

    private Double getDoubleFromArray(JsonNode parent, String field, int index) {
        JsonNode arr = parent.path(field);
        if (arr.isMissingNode() || !arr.isArray() || arr.size() <= index || arr.get(index).isNull()) {
            return null;
        }
        return arr.get(index).asDouble();
    }

    private Double toDouble(Object value) {
        if (value == null)
            return null;
        return ((Number) value).doubleValue();
    }

    // ──────────────────────────────────────────────
    // Manual backfill for N days (called from admin API)
    // ──────────────────────────────────────────────
    @Transactional
    public void backfillLastNDays(int days) {
        log.info("⏳ Manual historical backfill started for last {} days", days);
        long start = System.currentTimeMillis();

        List<WeatherNode> activeNodes = weatherNodeRepository.findByIsActiveTrue();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int totalFetched = 0;
        int totalSkipped = 0;

        for (int d = days; d >= 1; d--) {
            LocalDate date = today.minusDays(d);
            int fetched = 0;
            int skipped = 0;

            for (WeatherNode node : activeNodes) {
                try {
                    boolean exists = historicalDailyRepository
                            .findByWeatherNodeIdAndDateBetween(node.getId(), date, date)
                            .stream()
                            .anyMatch(h -> h.getDate().equals(date));

                    if (exists) {
                        skipped++;
                        continue;
                    }

                    boolean filled = fetchFromArchive(node, date);
                    if (filled)
                        fetched++;
                } catch (Exception e) {
                    log.error("Backfill failed for node {} on {}: {}", node.getCode(), date, e.getMessage());
                }
            }

            totalFetched += fetched;
            totalSkipped += skipped;

            if (d % 30 == 0) {
                log.info("  Backfill progress: {} days remaining, {} fetched, {} skipped so far",
                        d, totalFetched, totalSkipped);
            }
        }

        log.info("✅ Manual backfill completed in {}ms – {} fetched, {} skipped",
                System.currentTimeMillis() - start, totalFetched, totalSkipped);
    }
}
