package com.sidms.backend.scheduler;

import com.sidms.backend.client.ArcGISClient;
import com.sidms.backend.client.RivernetClient;
import com.sidms.backend.entity.FloodGaugeReading;
import com.sidms.backend.entity.RivernetDevice;
import com.sidms.backend.entity.enums.AlertLevel;
import com.sidms.backend.repository.FloodGaugeReadingRepository;
import com.sidms.backend.repository.RivernetDeviceRepository;
import com.sidms.backend.repository.SpatialUnitRepository;
import com.sidms.backend.entity.SpatialUnit;
import com.sidms.backend.entity.enums.SpatialType;
import com.sidms.backend.entity.enums.DisasterCategory;
import com.sidms.backend.entity.enums.DisasterSeverity;
import com.sidms.backend.service.DisasterWarningService;
import com.sidms.backend.service.SyncStateService;
import com.sidms.backend.util.CacheKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class FloodSyncScheduler {

    private static final String JOB_NAME = "flood_sync";
    private static final Duration COOLDOWN = Duration.ofMinutes(15);

    private final FloodGaugeReadingRepository floodGaugeReadingRepository;
    private final RivernetDeviceRepository rivernetDeviceRepository;
    private final SpatialUnitRepository spatialUnitRepository;
    private final DisasterWarningService disasterWarningService;
    private final ArcGISClient arcGISClient;
    private final RivernetClient rivernetClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final SyncStateService syncStateService;

    // ──────────────────────────────────────────────
    // Flood sync orchestrator: every 15 minutes
    // ──────────────────────────────────────────────
    @Scheduled(cron = "0 0/15 * * * *")
    public void scheduledFloodSync() {
        runWithStateTracking();
    }

    public void runWithStateTracking() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN))
            return;
        try {
            runFullSync();
            syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
        } catch (Exception e) {
            log.error("[{}] Sync failed: {}", JOB_NAME, e.getMessage(), e);
            syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
            throw e;
        }
    }

    public void runFullSync() {
        syncFloodGauges();
        syncRivernetDevices();
    }

    // ──────────────────────────────────────────────
    // Flood gauge sync: every 15 minutes
    // ──────────────────────────────────────────────
    @Transactional
    public void syncFloodGauges() {
        log.info("⏳ Flood gauge sync started");
        int upsertCount = 0;

        try {
            List<Map<String, Object>> gauges = arcGISClient.queryFeatures(ArcGISClient.SERVICES.get("gauges"), "1=1",
                    "*");

            for (Map<String, Object> attrs : gauges) {
                try {
                    String stationName = getStrMap(attrs, "gauge", "station", "station_name", "StationName");
                    String source = "arcgis";

                    if (stationName == null || stationName.isBlank())
                        continue;

                    Double waterLevel = getDblMap(attrs, "water_level", "WaterLevel", "WATER_LEVEL");
                    Double rainfall = getDblMap(attrs, "rain_fall", "Rainfall", "RAINFALL");
                    Double alertThreshold = getDblMap(attrs, "alertpull", "AlertLevel", "alert_threshold");
                    Double minorThreshold = getDblMap(attrs, "minorpull", "MinorFloodLevel", "minor_threshold");
                    Double majorThreshold = getDblMap(attrs, "majorpull", "MajorFloodLevel", "major_threshold");
                    Double lat = getDblMap(attrs, "Latitude", "LAT", "lat", "y");
                    Double lng = getDblMap(attrs, "Longitude", "LON", "lng", "lon", "x");
                    String basin = getStrMap(attrs, "basin", "BASIN");

                    // Determine recorded_at from epoch or current time
                    LocalDateTime recordedAt = LocalDateTime.now();
                    Object timeVal = firstNonNullMap(attrs, "CreationDate", "RecordedAt", "RECORDED_AT", "recorded_at",
                            "DateTime");
                    if (timeVal instanceof Number) {
                        long ts = ((Number) timeVal).longValue();
                        // ArcGIS timestamps are usually milliseconds
                        if (ts > 10000000000L) { // Milliseconds
                            recordedAt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts),
                                    java.time.ZoneId.of("Asia/Colombo"));
                        } else { // Seconds
                            recordedAt = LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(ts),
                                    java.time.ZoneId.of("Asia/Colombo"));
                        }
                    }

                    // Determine alert level
                    AlertLevel alertLevel = determineAlertLevel(waterLevel, alertThreshold, minorThreshold,
                            majorThreshold);

                    // Check for existing record (upsert by source+station+recordedAt)
                    List<FloodGaugeReading> existing = floodGaugeReadingRepository
                            .findTop1ByStationNameAndSourceOrderByRecordedAtDesc(stationName, source);

                    FloodGaugeReading reading;
                    if (!existing.isEmpty() && existing.get(0).getRecordedAt().equals(recordedAt)) {
                        reading = existing.get(0);
                    } else {
                        reading = new FloodGaugeReading();
                    }

                    reading.setSource(source);
                    reading.setStationName(stationName);
                    reading.setBasin(basin);
                    reading.setWaterLevel(waterLevel);
                    reading.setRainfall(rainfall);
                    reading.setAlertThreshold(alertThreshold);
                    reading.setMinorThreshold(minorThreshold);
                    reading.setMajorThreshold(majorThreshold);
                    reading.setAlertLevel(alertLevel);
                    reading.setLat(lat != null ? lat : 0.0);
                    reading.setLng(lng != null ? lng : 0.0);
                    reading.setRecordedAt(recordedAt);
                    reading.setFetchedAt(LocalDateTime.now());

                    floodGaugeReadingRepository.save(reading);
                    upsertCount++;

                    // Trigger automated warning if alert level is MINOR_FLOOD or higher
                    if (alertLevel == AlertLevel.MINOR_FLOOD || alertLevel == AlertLevel.MAJOR_FLOOD) {
                        triggerAutomatedFloodWarning(reading);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse flood feature: {}", e.getMessage());
                }
            }

            // Evict flood dashboard cache
            evictFloodCache();

            log.info("✅ Flood gauge sync completed – {} readings upserted", upsertCount);
        } catch (Exception e) {
            log.error("Flood gauge sync failed: {}", e.getMessage());
            throw new RuntimeException("Flood gauge sync failed", e);
        }
    }

    // ──────────────────────────────────────────────
    // Rivernet device sync: every 15 minutes
    // ──────────────────────────────────────────────
    @Transactional
    public void syncRivernetDevices() {
        log.info("⏳ Rivernet device sync started");
        int upsertCount = 0;

        try {
            JsonNode root = rivernetClient.getRegionDevices();
            if (root == null)
                return;

            List<Map.Entry<String, JsonNode>> allDevices = extractRivernetDevices(root);
            if (allDevices.isEmpty()) {
                log.warn("Rivernet response has no recognizable devices array/object");
                return;
            }

            java.util.Set<String> seenDeviceKeys = new java.util.HashSet<>();
            for (Map.Entry<String, JsonNode> entry : allDevices) {
                try {
                    String basinKey = entry.getKey();
                    JsonNode deviceNode = entry.getValue();
                    String deviceKey = getStr(deviceNode, "deviceKey", "device_key", "id");
                    if (deviceKey == null || deviceKey.isBlank())
                        continue;
                    if (!seenDeviceKeys.add(deviceKey)) {
                        continue;
                    }

                    RivernetDevice device = rivernetDeviceRepository.findByDeviceKey(deviceKey)
                            .orElse(RivernetDevice.builder()
                                    .deviceKey(deviceKey)
                                    .createdAt(LocalDateTime.now())
                                    .build());

                    device.setName(getStr(deviceNode, "name", "location", "title", "deviceKey", "device_key", "id"));
                    device.setBasin(getStr(deviceNode, "basin", "region", "river_basin") != null
                            ? getStr(deviceNode, "basin", "region", "river_basin")
                            : basinKey);
                    device.setRiver(getStr(deviceNode, "river"));
                    device.setDeviceType(getStr(deviceNode, "deviceType", "device_type", "type"));

                    JsonNode coordinatesNode = deviceNode.path("additional").path("coordinates");
                    device.setLat(getDblOrDefault(deviceNode, coordinatesNode, "lat", "latitude"));
                    device.setLng(getDblOrDefault(deviceNode, coordinatesNode, "lng", "lon", "longitude"));
                    device.setMaxLevel(getDbl(deviceNode, "maxLevel", "max_level"));
                    device.setOffsetValue(getDbl(deviceNode, "offset", "offsetValue", "offset_value"));
                    device.setIsOnline(deviceNode.has("isOnline") ? deviceNode.get("isOnline").asBoolean() : true);
                    device.setLastSyncedAt(LocalDateTime.now());
                    device.setUpdatedAt(LocalDateTime.now());

                    // Alert levels as JSON string (skipping for now due to JSONB mapping issues -
                    // will be handled with custom type)
                    // JsonNode alertLevels = deviceNode.path("alertLevels");
                    // if (!alertLevels.isMissingNode()) {
                    // device.setAlertLevels(alertLevels.toString());
                    // }

                    rivernetDeviceRepository.save(device);
                    upsertCount++;
                } catch (Exception e) {
                    log.warn("Failed to parse rivernet device: {}", e.getMessage());
                }
            }

            evictFloodCache();
            log.info("✅ Rivernet sync completed – {} devices upserted", upsertCount);
        } catch (Exception e) {
            log.error("Rivernet device sync failed: {}", e.getMessage());
            throw new RuntimeException("Rivernet device sync failed", e);
        }
    }

    private void triggerAutomatedFloodWarning(FloodGaugeReading reading) {
        try {
            // Find nearest GN division for the gauge
            List<SpatialUnit> gnDivisions = spatialUnitRepository.findByType(SpatialType.GN_DIVISION);
            SpatialUnit nearestGn = gnDivisions.stream()
                    .filter(su -> su.getLat() != null && su.getLng() != null)
                    .min(Comparator.comparingDouble(su -> Math.sqrt(
                            Math.pow(su.getLat() - reading.getLat(), 2) + Math.pow(su.getLng() - reading.getLng(), 2))))
                    .orElse(null);

            UUID spatialUnitId = nearestGn != null ? nearestGn.getId() : null;
            DisasterSeverity severity = (reading.getAlertLevel() == AlertLevel.MAJOR_FLOOD)
                    ? DisasterSeverity.EXTREME
                    : DisasterSeverity.HIGH;

            String headline = String.format("Flood Warning: %s (%s)", reading.getStationName(), reading.getBasin());
            String bulletin = String.format(
                    "Automated sensor detection: Water level at %s has reached %s level (%.2fm).",
                    reading.getStationName(), reading.getAlertLevel().name(), reading.getWaterLevel());

            disasterWarningService.createAutomatedWarning(
                    DisasterCategory.FLOOD,
                    severity,
                    headline,
                    bulletin,
                    spatialUnitId);
            log.info("📢 Automated flood warning triggered for {}", reading.getStationName());
        } catch (Exception e) {
            log.error("Failed to trigger automated flood warning for {}: {}", reading.getStationName(), e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────
    private void evictFloodCache() {
        try {
            stringRedisTemplate.delete(CacheKeys.floodDashboard());
        } catch (Exception e) {
            log.warn("Failed to evict flood dashboard cache: {}", e.getMessage());
        }
    }

    private AlertLevel determineAlertLevel(Double waterLevel, Double alertThreshold,
            Double minorThreshold, Double majorThreshold) {
        if (waterLevel == null)
            return AlertLevel.NORMAL;
        if (majorThreshold != null && waterLevel >= majorThreshold)
            return AlertLevel.MAJOR_FLOOD;
        if (minorThreshold != null && waterLevel >= minorThreshold)
            return AlertLevel.MINOR_FLOOD;
        if (alertThreshold != null && waterLevel >= alertThreshold)
            return AlertLevel.ALERT;
        return AlertLevel.NORMAL;
    }

    private Object firstNonNullMap(Map<String, Object> map, String... fields) {
        for (String f : fields) {
            Object v = map.get(f);
            if (v != null)
                return v;
        }
        return null;
    }

    private String getStrMap(Map<String, Object> map, String... fields) {
        Object v = firstNonNullMap(map, fields);
        return v != null ? v.toString() : null;
    }

    private Double getDblMap(Map<String, Object> map, String... fields) {
        Object v = firstNonNullMap(map, fields);
        if (v instanceof Number)
            return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble((String) v);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private JsonNode firstNonNull(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (!v.isMissingNode() && !v.isNull())
                return v;
        }
        return null;
    }

    private String getStr(JsonNode node, String... fields) {
        JsonNode v = firstNonNull(node, fields);
        return v != null ? v.asText() : null;
    }

    private String getStrOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? defaultValue : v.asText();
    }

    private Double getDbl(JsonNode node, String... fields) {
        JsonNode v = firstNonNull(node, fields);
        return v != null && v.isNumber() ? v.asDouble() : null;
    }

    private Double getDblOrDefault(JsonNode primaryNode, JsonNode secondaryNode, String... fields) {
        Double val = getDbl(primaryNode, fields);
        if (val == null && secondaryNode != null && !secondaryNode.isMissingNode()) {
            val = getDbl(secondaryNode, fields);
        }
        return val != null ? val : 0.0;
    }

    private List<Map.Entry<String, JsonNode>> extractRivernetDevices(JsonNode root) {
        List<Map.Entry<String, JsonNode>> out = new java.util.ArrayList<>();

        // Current Rivernet shape: { results: { basinRiverDevices: {...},
        // basinRainDevices: {...} } }
        JsonNode results = root.path("results");
        collectBasinObject(results.path("basinRiverDevices"), out);
        collectBasinObject(results.path("basinRainDevices"), out);

        // Legacy/fallback shape support
        JsonNode dataNode = root.path("data");
        JsonNode regionDevices = dataNode.path("region_devices");
        if (regionDevices.isObject()) {
            collectBasinObject(regionDevices, out);
        }

        if (root.isArray()) {
            root.forEach(node -> out.add(Map.entry("unknown", node)));
        } else if (root.path("devices").isArray()) {
            root.path("devices").forEach(node -> out.add(Map.entry("unknown", node)));
        }

        return out;
    }

    private void collectBasinObject(JsonNode basinObject, List<Map.Entry<String, JsonNode>> out) {
        if (!basinObject.isObject()) {
            return;
        }

        java.util.Iterator<Map.Entry<String, JsonNode>> fields = basinObject.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> basinEntry = fields.next();
            String basin = basinEntry.getKey();
            JsonNode devices = basinEntry.getValue();
            if (devices.isArray()) {
                devices.forEach(node -> out.add(Map.entry(basin, node)));
            }
        }
    }
}
