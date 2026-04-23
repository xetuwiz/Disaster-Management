package com.sidms.backend.scheduler;

import com.sidms.backend.dto.weather.WeatherResponse;
import com.sidms.backend.entity.AlertRule;
import com.sidms.backend.repository.AlertRuleRepository;
import com.sidms.backend.service.NotificationService;
import com.sidms.backend.service.SyncStateService;
import com.sidms.backend.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class AlertRuleEvaluator {

    private static final String   JOB_NAME = "alert_evaluation";
    private static final Duration COOLDOWN  = Duration.ofMinutes(15);

    private final SyncStateService    syncStateService;
    private final AlertRuleRepository alertRuleRepository;
    private final WeatherService      weatherService;
    private final NotificationService notificationService;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    // ──────────────────────────────────────────────
    // Evaluate alert rules: every 15 minutes
    // ──────────────────────────────────────────────
    @Scheduled(fixedDelayString = "${app.sync.alerts.interval}", initialDelayString = "${app.sync.alerts.initial-delay}")
    @Transactional
    public void scheduledEvaluateAlertRules() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN)) return;
        try {
            evaluateAlertRules();
            syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
        } catch (Exception e) {
            log.error("[AlertRuleEval] Sync failed: {}", e.getMessage(), e);
            syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
        }
    }

    /** Public entry point — also called by AdminSyncController for manual triggers. */
    @Transactional
    public void evaluateAlertRules() {
        log.info("⏳ Alert rule evaluation started");
        List<AlertRule> activeRules = alertRuleRepository.findByIsActiveTrue();

        if (activeRules.isEmpty()) {
            log.debug("No active alert rules to evaluate");
            return;
        }

        int triggered = 0;
        int skipped = 0;

        for (AlertRule rule : activeRules) {
            try {
                // 1. Check time window
                if (!isWithinTimeWindow(rule)) {
                    skipped++;
                    continue;
                }

                // 2. Check cooldown
                if (isInCooldown(rule)) {
                    skipped++;
                    continue;
                }

                // 3. Load current weather for spatial unit
                WeatherResponse weather;
                try {
                    weather = weatherService.getWeatherForSpatialUnit(rule.getSpatialUnitId());
                } catch (Exception e) {
                    log.warn("Could not get weather for spatial unit {} (rule {}): {}",
                            rule.getSpatialUnitId(), rule.getName(), e.getMessage());
                    continue;
                }

                // 4. Get parameter value
                Double paramValue = getParameterValue(weather, rule.getParameter());
                if (paramValue == null) {
                    log.debug("Parameter '{}' is null for spatial unit {}", rule.getParameter(), rule.getSpatialUnitId());
                    continue;
                }

                // 5. Evaluate operator
                boolean thresholdCrossed = evaluateCondition(paramValue, rule.getOperator(), rule.getThreshold());

                // 6. If threshold crossed → create notification
                if (thresholdCrossed) {
                    String title = String.format("Alert: %s", rule.getName());
                    String body = String.format(
                            "%s is currently %.1f, which %s your threshold of %.1f",
                            formatParameterName(rule.getParameter()),
                            paramValue,
                            describeOperator(rule.getOperator()),
                            rule.getThreshold());

                    notificationService.createNotification(
                            rule.getUserId(),
                            "ALERT_RULE",
                            title,
                            body,
                            rule.getSpatialUnitId(),
                            null);

                    rule.setLastTriggeredAt(LocalDateTime.now(ZoneOffset.UTC));
                    alertRuleRepository.save(rule);
                    triggered++;

                    log.info("Alert triggered: '{}' for user {} – {} = {}",
                            rule.getName(), rule.getUserId(), rule.getParameter(), paramValue);
                }
            } catch (Exception e) {
                log.error("Error evaluating alert rule '{}': {}", rule.getName(), e.getMessage());
            }
        }

        log.info("✅ Alert rule evaluation completed – {} triggered, {} skipped out of {} rules",
                triggered, skipped, activeRules.size());
    }

    // ──────────────────────────────────────────────
    // Time window check
    // ──────────────────────────────────────────────
    private boolean isWithinTimeWindow(AlertRule rule) {
        if (rule.getTimeWindowStart() == null || rule.getTimeWindowEnd() == null) {
            return true; // No time window restriction
        }

        try {
            LocalTime now = LocalTime.now();
            LocalTime start = LocalTime.parse(rule.getTimeWindowStart(), TIME_FORMAT);
            LocalTime end = LocalTime.parse(rule.getTimeWindowEnd(), TIME_FORMAT);

            if (start.isBefore(end)) {
                return !now.isBefore(start) && !now.isAfter(end);
            } else {
                // Crosses midnight (e.g., 22:00 – 06:00)
                return !now.isBefore(start) || !now.isAfter(end);
            }
        } catch (Exception e) {
            log.warn("Invalid time window format for rule '{}': {} - {}",
                    rule.getName(), rule.getTimeWindowStart(), rule.getTimeWindowEnd());
            return true; // If parsing fails, don't restrict
        }
    }

    // ──────────────────────────────────────────────
    // Cooldown check
    // ──────────────────────────────────────────────
    private boolean isInCooldown(AlertRule rule) {
        if (rule.getLastTriggeredAt() == null || rule.getCooldownHours() == null) {
            return false;
        }
        return rule.getLastTriggeredAt()
                .plusHours(rule.getCooldownHours())
                .isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }

    // ──────────────────────────────────────────────
    // Parameter value extraction via switch
    // ──────────────────────────────────────────────
    private Double getParameterValue(WeatherResponse weather, String parameter) {
        if (parameter == null) return null;

        return switch (parameter.toLowerCase()) {
            case "precipitation_mm" -> weather.getPrecipitationMm();
            case "temp_c" -> weather.getTempC();
            case "wind_speed_kmh" -> weather.getWindSpeedKmh();
            case "cape_jkg" -> weather.getCapeJkg();
            case "humidity_pct" -> weather.getHumidityPct();
            case "uv_index" -> weather.getUvIndex();
            case "apparent_temp_c" -> weather.getApparentTempC();
            case "pressure_hpa" -> weather.getPressureHpa();
            case "wind_gust_kmh" -> weather.getWindGustKmh();
            case "cloud_cover_pct" -> weather.getCloudCoverPct();
            default -> {
                log.warn("Unknown alert parameter: {}", parameter);
                yield null;
            }
        };
    }

    // ──────────────────────────────────────────────
    // Operator evaluation
    // ──────────────────────────────────────────────
    private boolean evaluateCondition(double value, String operator, double threshold) {
        return switch (operator.toUpperCase()) {
            case "GT" -> value > threshold;
            case "LT" -> value < threshold;
            case "GTE" -> value >= threshold;
            case "LTE" -> value <= threshold;
            case "EQ" -> Math.abs(value - threshold) < 0.01;
            default -> {
                log.warn("Unknown operator: {}", operator);
                yield false;
            }
        };
    }

    // ──────────────────────────────────────────────
    // Formatting helpers
    // ──────────────────────────────────────────────
    private String formatParameterName(String parameter) {
        return switch (parameter.toLowerCase()) {
            case "precipitation_mm" -> "Precipitation";
            case "temp_c" -> "Temperature";
            case "wind_speed_kmh" -> "Wind Speed";
            case "cape_jkg" -> "CAPE";
            case "humidity_pct" -> "Humidity";
            case "uv_index" -> "UV Index";
            case "apparent_temp_c" -> "Apparent Temperature";
            case "pressure_hpa" -> "Pressure";
            case "wind_gust_kmh" -> "Wind Gust";
            case "cloud_cover_pct" -> "Cloud Cover";
            default -> parameter;
        };
    }

    private String describeOperator(String operator) {
        return switch (operator.toUpperCase()) {
            case "GT" -> "exceeds";
            case "LT" -> "is below";
            case "GTE" -> "meets or exceeds";
            case "LTE" -> "meets or is below";
            case "EQ" -> "equals";
            default -> "triggered against";
        };
    }
}
