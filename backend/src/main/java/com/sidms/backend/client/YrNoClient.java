package com.sidms.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidms.backend.util.ApiCircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Yr.no (ECMWF-backed) location forecast client.
 *
 * Spec:
 * - Primary weather model: 0–72h hourly timeseries
 * - Rate limit: 1 req/sec enforced by Yr.no; 429 triggers jittered sleep +
 * retry
 * - Must identify itself with a proper User-Agent or receive 403
 * - Wrapped in circuit breaker — trips after 5 consecutive failures
 */
@Component
@Slf4j
public class YrNoClient {

    private static final String SERVICE = "YRNO";
    private static final String BASE_URL_COMPACT = "https://api.met.no/weatherapi/locationforecast/2.0/compact";
    private static final String BASE_URL_COMPLETE = "https://api.met.no/weatherapi/locationforecast/2.0/complete";
    private static final String USER_AGENT = "ClimaSphere/1.0 (admin@climasphere.lk)";

    private static final int MAX_RETRIES = 3;
    private static final int MAX_TS_ENTRIES = 72;

    // Base delays for timeout/network retries (ms): attempt 1=2s, 2=4s, 3=8s
    private static final long[] NETWORK_DELAYS = { 2_000L, 4_000L, 8_000L };

    // 429 jitter range: 60–70 seconds
    private static final long RATE_LIMIT_MIN = 60_000L;
    private static final long RATE_LIMIT_MAX = 70_000L;

    private final RestTemplate restTemplate;
    private final ApiCircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;

    @Value("${app.sync.yrno.endpoint-mode:complete}")
    private String endpointMode;

    public YrNoClient(RestTemplate restTemplate,
            ApiCircuitBreaker circuitBreaker,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Fetches the compact location forecast from Yr.no for the given coordinates.
     * Retries on 429 (rate-limited) and network/timeout errors.
     * Throws on 403 (bad User-Agent) or circuit breaker open.
     *
     * @param lat latitude in decimal degrees
     * @param lon longitude in decimal degrees
     * @return parsed JsonNode of the full Yr.no response
     */
    public JsonNode fetchNode(double lat, double lon) {
        log.info("[YrNo] request-start lat={} lon={}", lat, lon);
        return circuitBreaker.execute(SERVICE, () -> executeWithRetry(lat, lon));
    }

    /**
     * Parses the Yr.no timeseries response into a flat list of
     * {@link NodeTimeseriesData}.
     * Processes up to 72 hourly entries; skips any entry missing air_temperature.
     *
     * @param root   parsed Yr.no JSON root node
     * @param nodeId the WeatherNode UUID this timeseries belongs to
     * @return list of timeseries data records, never null
     */
    public List<NodeTimeseriesData> parseTimeseries(JsonNode root, UUID nodeId) {
        List<NodeTimeseriesData> results = new ArrayList<>();

        JsonNode timeseries = root.path("properties").path("timeseries");
        if (!timeseries.isArray()) {
            log.warn("[YrNo] parseTimeseries: no timeseries array found for node {}", nodeId);
            return results;
        }

        int forecastHour = 0;
        for (JsonNode entry : timeseries) {
            if (forecastHour >= MAX_TS_ENTRIES)
                break;

            // --- valid_from_utc ---
            LocalDateTime validFrom = parseTime(entry);
            if (validFrom == null) {
                forecastHour++;
                continue;
            }

            // --- instant details ---
            JsonNode details = entry.path("data").path("instant").path("details");

            Double temperatureC = doubleOrNull(details, "air_temperature");
            if (temperatureC == null) {
                // Skip entries with no temperature (data gaps at end of timeseries)
                forecastHour++;
                continue;
            }

            Double humidityPct = doubleOrNull(details, "relative_humidity");
            Double windSpeedMs = doubleOrNull(details, "wind_speed");
            Integer windDirectionDeg = intOrNull(details, "wind_from_direction");
            Double pressureHpa = doubleOrNull(details, "air_pressure_at_sea_level");

            Double cloudCoverPct = doubleOrNull(details, "cloud_area_fraction");
            Double cloudCoverHighPct = doubleOrNull(details, "cloud_area_fraction_high");
            Double cloudCoverLowPct = doubleOrNull(details, "cloud_area_fraction_low");
            Double cloudCoverMediumPct = doubleOrNull(details, "cloud_area_fraction_medium");
            Double dewPointC = doubleOrNull(details, "dew_point_temperature");
            Double fogAreaFractionPct = doubleOrNull(details, "fog_area_fraction");
            Double uvIndexClearSky = doubleOrNull(details, "ultraviolet_index_clear_sky");
            Double windGustMs = doubleOrNull(details, "wind_speed_of_gust");
            Double tempPercentile10 = doubleOrNull(details, "air_temperature_percentile_10");
            Double tempPercentile90 = doubleOrNull(details, "air_temperature_percentile_90");
            Double windPercentile10 = doubleOrNull(details, "wind_speed_percentile_10");
            Double windPercentile90 = doubleOrNull(details, "wind_speed_percentile_90");

            // --- forecast windows (fields may exist in different windows by timestep) ---
            JsonNode next1HoursDetails = entry.path("data").path("next_1_hours").path("details");
            JsonNode next6HoursDetails = entry.path("data").path("next_6_hours").path("details");
            JsonNode next12HoursDetails = entry.path("data").path("next_12_hours").path("details");

            // Keep hourly precipitation semantics strict: only next_1_hours is 1-hour data.
            Double precipitationMm = doubleOrNull(next1HoursDetails, "precipitation_amount");
            Double precipMinMm = firstNonNull(
                    doubleOrNull(next1HoursDetails, "precipitation_amount_min"),
                    doubleOrNull(next6HoursDetails, "precipitation_amount_min"),
                    doubleOrNull(next12HoursDetails, "precipitation_amount_min"));
            Double precipMaxMm = firstNonNull(
                    doubleOrNull(next1HoursDetails, "precipitation_amount_max"),
                    doubleOrNull(next6HoursDetails, "precipitation_amount_max"),
                    doubleOrNull(next12HoursDetails, "precipitation_amount_max"));
            Double precipProbPct = firstNonNull(
                    doubleOrNull(next1HoursDetails, "probability_of_precipitation"),
                    doubleOrNull(next6HoursDetails, "probability_of_precipitation"),
                    doubleOrNull(next12HoursDetails, "probability_of_precipitation"));
            Double thunderProbPct = firstNonNull(
                    doubleOrNull(next1HoursDetails, "probability_of_thunder"),
                    doubleOrNull(next6HoursDetails, "probability_of_thunder"),
                    doubleOrNull(next12HoursDetails, "probability_of_thunder"));

            Double tempMaxC = firstNonNull(
                    doubleOrNull(next6HoursDetails, "air_temperature_max"),
                    doubleOrNull(next1HoursDetails, "air_temperature_max"),
                    doubleOrNull(next12HoursDetails, "air_temperature_max"));
            Double tempMinC = firstNonNull(
                    doubleOrNull(next6HoursDetails, "air_temperature_min"),
                    doubleOrNull(next1HoursDetails, "air_temperature_min"),
                    doubleOrNull(next12HoursDetails, "air_temperature_min"));

            // --- symbol code ---
            JsonNode next1HoursSummary = entry.path("data").path("next_1_hours").path("summary");
            JsonNode next6HoursSummary = entry.path("data").path("next_6_hours").path("summary");
            JsonNode next12HoursSummary = entry.path("data").path("next_12_hours").path("summary");
            String symbolCode = firstNonNull(
                    stringOrNull(next1HoursSummary, "symbol_code"),
                    stringOrNull(next6HoursSummary, "symbol_code"),
                    stringOrNull(next12HoursSummary, "symbol_code"));

            results.add(NodeTimeseriesData.builder()
                    .nodeId(nodeId)
                    .validFromUtc(validFrom)
                    .forecastHour(forecastHour)
                    .temperatureC(temperatureC)
                    .humidityPct(humidityPct)
                    .windSpeedMs(windSpeedMs)
                    .windDirectionDeg(windDirectionDeg)
                    .pressureHpa(pressureHpa)
                    .precipitationMm(precipitationMm)
                    .cloudCoverPct(cloudCoverPct)
                    .cloudCoverHighPct(cloudCoverHighPct)
                    .cloudCoverLowPct(cloudCoverLowPct)
                    .cloudCoverMediumPct(cloudCoverMediumPct)
                    .dewPointC(dewPointC)
                    .fogAreaFractionPct(fogAreaFractionPct)
                    .uvIndexClearSky(uvIndexClearSky)
                    .windGustMs(windGustMs)
                    .tempPercentile10(tempPercentile10)
                    .tempPercentile90(tempPercentile90)
                    .windPercentile10(windPercentile10)
                    .windPercentile90(windPercentile90)
                    .precipProbPct(precipProbPct)
                    .thunderProbPct(thunderProbPct)
                    .precipMinMm(precipMinMm)
                    .precipMaxMm(precipMaxMm)
                    .tempMaxC(tempMaxC)
                    .tempMinC(tempMinC)
                    .symbolCode(symbolCode)
                    .build());

            forecastHour++;
        }

        log.info("[YrNo] parseTimeseries: {} entries parsed for node {}", results.size(), nodeId);
        return results;
    }

    // =========================================================================
    // Inner data record
    // =========================================================================

    /**
     * Flat projection of one hourly timeseries entry, ready for conversion to
     * {@link com.sidms.backend.entity.NodeTimeseries}.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class NodeTimeseriesData {
        private UUID nodeId;
        private LocalDateTime validFromUtc;
        private int forecastHour;
        private Double temperatureC;
        private Double humidityPct;
        private Double windSpeedMs;
        private Integer windDirectionDeg;
        private Double pressureHpa;
        private Double precipitationMm;
        private Double cloudCoverPct;
        private Double cloudCoverHighPct;
        private Double cloudCoverLowPct;
        private Double cloudCoverMediumPct;
        private Double dewPointC;
        private Double fogAreaFractionPct;
        private Double uvIndexClearSky;
        private Double windGustMs;
        private Double tempPercentile10;
        private Double tempPercentile90;
        private Double windPercentile10;
        private Double windPercentile90;
        private Double precipProbPct;
        private Double thunderProbPct;
        private Double precipMinMm;
        private Double precipMaxMm;
        private Double tempMaxC;
        private Double tempMinC;
        private Integer weatherCode; // reserved — Yr.no compact has no numeric code
        private String symbolCode;
    }

    // =========================================================================
    // Private — retry logic
    // =========================================================================

    private JsonNode executeWithRetry(double lat, double lon) {
        String url = buildUrl(lat, lon);
        HttpEntity<Void> request = buildRequest();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

                JsonNode node = objectMapper.readTree(response.getBody());
                log.info("[YrNo] success attempt={} lat={} lon={}", attempt, lat, lon);
                return node;

            } catch (HttpClientErrorException.Forbidden e) {
                // 403 — misconfigured User-Agent; retrying is pointless
                log.error("[YrNo] Yr.no rejected request: missing or invalid User-Agent");
                throw e;

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    handleRateLimit(attempt, lat, lon, e);
                } else {
                    // 4xx other than 403/429 — do not retry
                    log.error("[YrNo] failed attempt={} error={}", attempt, e.getMessage(), e);
                    throw e;
                }

            } catch (Exception e) {
                // Network error / timeout
                if (attempt == MAX_RETRIES) {
                    log.error("[YrNo] failed attempt={} error={}", attempt, e.getMessage(), e);
                    throw new RuntimeException("[YrNo] all retries exhausted lat=" + lat + " lon=" + lon, e);
                }
                log.warn("[YrNo] failed attempt={} error={} — retrying in {}ms",
                        attempt, e.getMessage(), NETWORK_DELAYS[attempt - 1]);
                sleep(NETWORK_DELAYS[attempt - 1]);
            }
        }

        // Should not be reached — final attempt throws above
        throw new RuntimeException("[YrNo] all retries exhausted lat=" + lat + " lon=" + lon);
    }

    private void handleRateLimit(int attempt, double lat, double lon, Exception e) {
        if (attempt == MAX_RETRIES) {
            log.error("[YrNo] failed attempt={} error={}", attempt, e.getMessage(), e);
            throw new RuntimeException("[YrNo] rate-limited after " + MAX_RETRIES + " attempts", e);
        }
        long jitter = ThreadLocalRandom.current().nextLong(RATE_LIMIT_MIN, RATE_LIMIT_MAX + 1);
        log.warn("[YrNo] 429 rate-limited lat={} lon={} — attempt={} sleeping {}ms", lat, lon, attempt, jitter);
        sleep(jitter);
    }

    // =========================================================================
    // Private — HTTP helpers
    // =========================================================================

    private String buildUrl(double lat, double lon) {
        String mode = endpointMode == null ? "compact" : endpointMode.trim().toLowerCase();
        String baseUrl = "complete".equals(mode) ? BASE_URL_COMPLETE : BASE_URL_COMPACT;
        return String.format("%s?lat=%.4f&lon=%.4f", baseUrl, lat, lon);
    }

    private HttpEntity<Void> buildRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        return new HttpEntity<>(headers);
    }

    // =========================================================================
    // Private — JSON field extractors
    // =========================================================================

    private LocalDateTime parseTime(JsonNode entry) {
        try {
            String raw = entry.path("time").asText(null);
            if (raw == null || raw.isEmpty())
                return null;
            // Yr.no uses ISO-8601 with Z suffix: "2024-01-15T06:00:00Z"
            return LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        } catch (Exception e) {
            log.warn("[YrNo] Failed to parse time node: {}", entry.path("time"));
            return null;
        }
    }

    private Double doubleOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asDouble();
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asInt();
    }

    private String stringOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asText(null);
    }

    @SafeVarargs
    private <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("[YrNo] Sleep interrupted", ie);
        }
    }
}
