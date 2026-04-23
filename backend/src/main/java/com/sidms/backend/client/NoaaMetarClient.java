package com.sidms.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches METAR observations from the NOAA Aviation Weather API for Sri Lanka airports.
 *
 * Active stations: VCBI (Katunayake / BIA), VCRI (Mattala / MRIA).
 * Frequency: every 30 minutes.
 * API:  https://aviationweather.gov/api/data/metar?ids=VCBI,VCRI&format=json
 * Auth: none (public endpoint).
 *
 * Unit conversions applied:
 *   wind speed : knots → m/s  (× 0.514444)
 *   altimeter  : inHg  → hPa  (× 33.8639)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NoaaMetarClient {

    private static final String METAR_URL =
            "https://aviationweather.gov/api/data/metar?ids=VCBI,VCRI&format=json";

    // Since Sept 2025, obsTime is a Unix epoch integer (e.g. 1776834600).
    // reportTime is still an ISO-8601 string fallback.

    // ICAO code → station_metadata.station_id mapping
    private static final Map<String, String> ICAO_TO_STATION_ID =
            Map.of("VCBI", "VCBI", "VCRI", "VCRI");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Fetches the latest METAR observation for both Sri Lanka ICAO stations.
     * Returns an empty list on any network or parse error.
     *
     * @return list of parsed readings; never null
     */
    public List<MetarReading> fetchLatestReadings() {
        List<MetarReading> readings = new ArrayList<>();

        try {
            log.info("[NoaaMETAR] Fetching METAR observations from {}", METAR_URL);
            String json = restTemplate.getForObject(METAR_URL, String.class);

            if (json == null || json.isBlank()) {
                log.warn("[NoaaMETAR] Empty response from METAR API");
                return readings;
            }

            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                log.warn("[NoaaMETAR] Unexpected response structure — expected JSON array");
                return readings;
            }

            for (JsonNode obs : root) {
                try {
                    MetarReading reading = parseObservation(obs);
                    if (reading != null) readings.add(reading);
                } catch (Exception e) {
                    log.warn("[NoaaMETAR] Failed to parse observation {}: {}",
                            obs.path("icaoId").asText("?"), e.getMessage());
                }
            }

            log.info("[NoaaMETAR] Parsed {} METAR readings", readings.size());

        } catch (Exception e) {
            log.warn("[NoaaMETAR] Failed to fetch METAR observations: {}", e.getMessage(), e);
        }

        return readings;
    }

    // =========================================================================
    // Inner data record
    // =========================================================================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MetarReading {
        private String        stationId;
        private LocalDateTime timestampUtc;
        private Double        temperatureC;
        private Double        dewPointC;
        private Double        windSpeedMs;
        private Integer       windDirectionDeg;
        private Double        pressureHpa;
        private Double        humidityPct;
        private String        weatherType;
        /** Always null — METAR reports do not include precipitation totals. */
        private Double        rainfallMm;
    }

    // =========================================================================
    // Private — observation parsing
    // =========================================================================

    private MetarReading parseObservation(JsonNode obs) {
        // Resolve ICAO → station_id
        String icao = obs.path("icaoId").asText(null);
        if (icao == null) icao = obs.path("station_id").asText(null);

        String stationId = icao != null ? ICAO_TO_STATION_ID.get(icao) : null;
        if (stationId == null) {
            log.warn("[NoaaMETAR] No station mapping for ICAO code: {}", icao);
            return null;
        }

        // obsTime: Unix epoch integer since Sept 2025 NOAA API update.
        // Fall back to reportTime (ISO-8601 string) if obsTime is absent.
        LocalDateTime timestampUtc;
        JsonNode obsTimeNode = obs.path("obsTime");
        if (!obsTimeNode.isMissingNode() && !obsTimeNode.isNull() && obsTimeNode.isNumber()) {
            timestampUtc = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(obsTimeNode.asLong()), ZoneOffset.UTC);
        } else {
            // Fallback: reportTime is "2026-04-21 14:30:00.000Z" or ISO-8601
            String reportTime = obs.path("reportTime").asText(null);
            if (reportTime == null || reportTime.isBlank()) {
                log.warn("[NoaaMETAR] Missing both obsTime and reportTime for station {}", stationId);
                return null;
            }
            // Strip trailing .000Z if present, then parse
            reportTime = reportTime.replace(".000Z", "Z").replace(" ", "T");
            if (!reportTime.endsWith("Z")) reportTime += "Z";
            timestampUtc = LocalDateTime.ofInstant(
                    Instant.parse(reportTime), ZoneOffset.UTC);
        }

        // Temperature (°C) — direct
        Double temperatureC = doubleOrNull(obs, "temp");

        // Dew point (°C) — direct
        Double dewPointC = doubleOrNull(obs, "dewp");

        // Relative Humidity approximation
        Double humidityPct = null;
        if (temperatureC != null && dewPointC != null) {
            humidityPct = 100.0 * (Math.exp((17.625 * dewPointC) / (243.04 + dewPointC)) / Math.exp((17.625 * temperatureC) / (243.04 + temperatureC)));
            if (humidityPct > 100.0) humidityPct = 100.0;
            humidityPct = Math.round(humidityPct * 10.0) / 10.0;
        }

        // Wind speed: knots → m/s
        Double windSpeedKts = doubleOrNull(obs, "wspd");
        Double windSpeedMs  = windSpeedKts != null ? windSpeedKts * 0.514444 : null;

        // Wind direction (degrees true)
        Integer windDirectionDeg = intOrNull(obs, "wdir");

        // Weather type heuristics
        String weatherType = null;
        String wxString = obs.path("wxString").asText("").toUpperCase();
        String cover = obs.path("cover").asText("").toUpperCase();

        if (wxString.contains("TS")) {
            weatherType = "thunderstorms";
        } else if (wxString.contains("RA") || wxString.contains("DZ") || wxString.contains("SH")) {
            weatherType = "showers";
        } else if (wxString.contains("FG") || wxString.contains("BR") || wxString.contains("HZ")) {
            weatherType = "fog";
        } else if (cover.equals("OVC")) {
            weatherType = "cloudy";
        } else if (cover.equals("SCT") || cover.equals("BKN")) {
            weatherType = "partlycloudy";
        } else if (cover.equals("CLR") || cover.equals("SKC") || cover.equals("CAVOK") || cover.equals("FEW")) {
            weatherType = "fair";
        }

        // Altimeter setting: inHg → hPa  (1 inHg = 33.8639 hPa)
        Double altimValue = doubleOrNull(obs, "altim");
        Double pressureHpa = null;
        if (altimValue != null) {
            if (altimValue < 50.0) {
                // It's inHg
                pressureHpa = altimValue * 33.8639;
            } else {
                // It's already in hPa
                pressureHpa = altimValue;
            }
        }

        return MetarReading.builder()
                .stationId(stationId)
                .timestampUtc(timestampUtc)
                .temperatureC(temperatureC)
                .dewPointC(dewPointC)
                .windSpeedMs(windSpeedMs)
                .windDirectionDeg(windDirectionDeg)
                .pressureHpa(pressureHpa)
                .humidityPct(humidityPct)
                .weatherType(weatherType)
                .rainfallMm(null)      // METAR does not report precipitation totals
                .build();
    }

    // =========================================================================
    // Private — JSON field helpers
    // =========================================================================

    private Double doubleOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asDouble();
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asInt();
    }
}
