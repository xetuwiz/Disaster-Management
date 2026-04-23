package com.sidms.backend.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.sidms.backend.client.MetSunriseClient;
import com.sidms.backend.entity.WeatherNode;
import com.sidms.backend.entity.WeatherNodeCelestial;
import com.sidms.backend.repository.WeatherNodeCelestialRepository;
import com.sidms.backend.repository.WeatherNodeRepository;
import com.sidms.backend.service.SyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class MetCelestialSyncScheduler {

    private static final String JOB_NAME = "met_celestial_sync";
    // Sync celestial data once a day
    private static final Duration COOLDOWN = Duration.ofHours(20);

    private final SyncStateService syncStateService;
    private final WeatherNodeRepository weatherNodeRepository;
    private final WeatherNodeCelestialRepository celestialRepository;
    private final MetSunriseClient sunriseClient;

    @Value("${app.sync.celestial.enabled:true}")
    private boolean celestialSyncEnabled;

    @Scheduled(cron = "0 5 0 * * *") // Run at 00:05 everyday
    public void scheduledSyncCelestialData() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN))
            return;
        try {
            if (!celestialSyncEnabled) {
                log.info("Celestial sync disabled.");
                syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
                return;
            }
            syncCelestialData();
            syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
        } catch (Exception e) {
            log.error("[{}] Sync failed: {}", JOB_NAME, e.getMessage(), e);
            syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
        }
    }

    @Transactional
    public void syncCelestialData() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        log.info("⏳ Celestial sync started runId={}", runId);
        long start = System.currentTimeMillis();

        List<WeatherNode> activeNodes = weatherNodeRepository.findByIsActiveTrue();
        if (activeNodes.isEmpty()) {
            log.warn("No active weather nodes found – skipping celestial sync");
            return;
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int successCount = 0;

        for (WeatherNode node : activeNodes) {
            try {
                // Calculate timezone offset approx based on longitude (15 deg = 1 hour)
                // This is a rough estimate. For more exact, you might need a timezone library,
                // but MET API says offset is optional. If omitted, times are in UTC which is
                // fine for our DB.
                // We'll omit offset to store exactly as UTC.

                WeatherNodeCelestial celestial = celestialRepository
                        .findByWeatherNodeIdAndRecordDate(node.getId(), today)
                        .orElse(WeatherNodeCelestial.builder()
                                .weatherNodeId(node.getId())
                                .recordDate(today)
                                .build());

                // Fetch Sun
                JsonNode sunData = sunriseClient.getSunEvents(node.getLat(), node.getLng(), today, null);
                parseSunData(celestial, sunData);

                // Fetch Moon
                JsonNode moonData = sunriseClient.getMoonEvents(node.getLat(), node.getLng(), today, null);
                parseMoonData(celestial, moonData);

                celestialRepository.save(celestial);
                successCount++;

                // Respect MET Norway rate limit (1 req/sec)
                Thread.sleep(1100);

            } catch (Exception e) {
                log.warn("Failed to fetch celestial data for node {} ({}): {}", node.getCode(), node.getId(),
                        e.getMessage());
            }
        }

        log.info("✅ Celestial sync completed runId={} in {}ms – updated {}/{} nodes",
                runId, System.currentTimeMillis() - start, successCount, activeNodes.size());
    }

    private void parseSunData(WeatherNodeCelestial entity, JsonNode root) {
        if (root == null)
            return;
        JsonNode props = root.path("properties");
        if (props.isMissingNode())
            return;

        JsonNode sunrise = props.path("sunrise");
        if (!sunrise.isMissingNode() && !sunrise.isNull()) {
            entity.setSunriseTime(parseOffsetDateTime(sunrise.path("time").asText(null)));
            entity.setSunriseAzimuth(readNullableDouble(sunrise, "azimuth"));
        } else {
            entity.setSunriseTime(null);
            entity.setSunriseAzimuth(null);
        }

        JsonNode sunset = props.path("sunset");
        if (!sunset.isMissingNode() && !sunset.isNull()) {
            entity.setSunsetTime(parseOffsetDateTime(sunset.path("time").asText(null)));
            entity.setSunsetAzimuth(readNullableDouble(sunset, "azimuth"));
        } else {
            entity.setSunsetTime(null);
            entity.setSunsetAzimuth(null);
        }

        JsonNode solarnoon = props.path("solarnoon");
        if (!solarnoon.isMissingNode() && !solarnoon.isNull()) {
            entity.setSolarnoonTime(parseOffsetDateTime(solarnoon.path("time").asText(null)));
            entity.setSolarnoonElevation(readNullableDouble(solarnoon, "disc_centre_elevation"));
        } else {
            entity.setSolarnoonTime(null);
            entity.setSolarnoonElevation(null);
        }

        JsonNode solarmidnight = props.path("solarmidnight");
        if (!solarmidnight.isMissingNode() && !solarmidnight.isNull()) {
            entity.setSolarmidnightTime(parseOffsetDateTime(solarmidnight.path("time").asText(null)));
            entity.setSolarmidnightElevation(readNullableDouble(solarmidnight, "disc_centre_elevation"));
        } else {
            entity.setSolarmidnightTime(null);
            entity.setSolarmidnightElevation(null);
        }
    }

    private void parseMoonData(WeatherNodeCelestial entity, JsonNode root) {
        if (root == null)
            return;
        JsonNode props = root.path("properties");
        if (props.isMissingNode())
            return;

        JsonNode moonrise = props.path("moonrise");
        if (!moonrise.isMissingNode() && !moonrise.isNull()) {
            entity.setMoonriseTime(parseOffsetDateTime(moonrise.path("time").asText(null)));
            entity.setMoonriseAzimuth(readNullableDouble(moonrise, "azimuth"));
        } else {
            entity.setMoonriseTime(null);
            entity.setMoonriseAzimuth(null);
        }

        JsonNode moonset = props.path("moonset");
        if (!moonset.isMissingNode() && !moonset.isNull()) {
            entity.setMoonsetTime(parseOffsetDateTime(moonset.path("time").asText(null)));
            entity.setMoonsetAzimuth(readNullableDouble(moonset, "azimuth"));
        } else {
            entity.setMoonsetTime(null);
            entity.setMoonsetAzimuth(null);
        }

        JsonNode highMoon = props.path("high_moon");
        if (!highMoon.isMissingNode() && !highMoon.isNull()) {
            entity.setHighMoonTime(parseOffsetDateTime(highMoon.path("time").asText(null)));
            entity.setHighMoonElevation(readNullableDouble(highMoon, "disc_centre_elevation"));
        } else {
            entity.setHighMoonTime(null);
            entity.setHighMoonElevation(null);
        }

        JsonNode lowMoon = props.path("low_moon");
        if (!lowMoon.isMissingNode() && !lowMoon.isNull()) {
            entity.setLowMoonTime(parseOffsetDateTime(lowMoon.path("time").asText(null)));
            entity.setLowMoonElevation(readNullableDouble(lowMoon, "disc_centre_elevation"));
        } else {
            entity.setLowMoonTime(null);
            entity.setLowMoonElevation(null);
        }

        JsonNode moonphase = props.path("moonphase");
        if (!moonphase.isMissingNode() && !moonphase.isNull()) {
            if (moonphase.isNumber()) {
                entity.setMoonphase(moonphase.asDouble());
            } else {
                entity.setMoonphase(readNullableDouble(moonphase, "value"));
            }
        } else {
            entity.setMoonphase(null);
        }
    }

    private Double readNullableDouble(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }

    private OffsetDateTime parseOffsetDateTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank())
            return null;
        try {
            return OffsetDateTime.parse(timeStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }
}
