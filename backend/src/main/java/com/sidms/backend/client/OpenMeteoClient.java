package com.sidms.backend.client;

import com.sidms.backend.config.ApiKeyConfig;
import com.sidms.backend.util.ApiKeyManager;
import com.sidms.backend.util.CacheKeys;
import com.sidms.backend.util.ApiCircuitBreaker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Client for Open-Meteo APIs: forecast, historical, and air quality.
 * Routes through CircuitBreaker and ApiKeyManager.
 */
@Component
@Slf4j
public class OpenMeteoClient {

    private static final String SERVICE = "OPENMETEO";
    private static final String FORECAST_BASE = "https://api.open-meteo.com/v1/forecast";
    private static final String HISTORICAL_BASE = "https://historical-forecast-api.open-meteo.com/v1/forecast";
    private static final String AIR_QUALITY_BASE = "https://air-quality-api.open-meteo.com/v1/air-quality";
    private static final String ENSEMBLE_BASE = "https://ensemble-api.open-meteo.com/v1/ensemble";

    private final RestTemplate restTemplate;
    private final ApiKeyManager apiKeyManager;
    private final ApiCircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.sync.debug.verbose:false}")
    private boolean verboseSyncDebug;

    public OpenMeteoClient(RestTemplate restTemplate, ApiKeyManager apiKeyManager,
            ApiCircuitBreaker circuitBreaker, ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate) {
        this.restTemplate = restTemplate;
        this.apiKeyManager = apiKeyManager;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Fetch current weather for a single coordinate.
     */
    public JsonNode getCurrentSingle(double lat, double lng, String currentParams) {
        String url = FORECAST_BASE + "?latitude=" + lat + "&longitude=" + lng + "&" + currentParams;
        return fetchWithRetry(url);
    }

    /**
     * Fetch current weather for a batch of coordinates (comma-separated).
     */
    public JsonNode getCurrentBatch(String latsCsv, String lngsCsv, String currentParams) {
        String url = FORECAST_BASE + "?latitude=" + latsCsv + "&longitude=" + lngsCsv + "&" + currentParams;
        return fetchWithRetry(url);
    }

    /**
     * Fetch ensemble forecast for a batch of coordinates.
     */
    public JsonNode getEnsembleBatch(String latsCsv, String lngsCsv, String ensembleParams) {
        String url = ENSEMBLE_BASE + "?latitude=" + latsCsv + "&longitude=" + lngsCsv + "&" + ensembleParams;
        return fetchWithRetry(url);
    }

    /**
     * Fetch historical daily data for a coordinate range.
     */
    public JsonNode getHistoricalDaily(double lat, double lng, String startDate, String endDate, String dailyParams) {
        String url = HISTORICAL_BASE + "?latitude=" + lat + "&longitude=" + lng
                + "&start_date=" + startDate + "&end_date=" + endDate
                + "&timezone=auto&" + dailyParams;
        return fetchWithRetry(url);
    }

    public JsonNode getArchiveDaily(double lat, double lng, String startDate, String endDate, String dailyParams) {
        String url = "https://archive-api.open-meteo.com/v1/archive?latitude=" + lat + "&longitude=" + lng
                + "&start_date=" + startDate + "&end_date=" + endDate
                + "&timezone=auto&" + dailyParams;
        return fetchWithRetry(url);
    }

    /**
     * Fetch Air Quality Index for a coordinate (AQI, PM10, PM2.5).
     * This was completely missing from our backend.
     */
    public JsonNode getAirQualityCurrent(double lat, double lng) {
        String url = AIR_QUALITY_BASE + "?latitude=" + lat + "&longitude=" + lng
                + "&current=us_aqi,pm10,pm2_5";
        try {
            return fetchWithRetry(url);
        } catch (Exception e) {
            log.warn("AQI fetch failed for {},{}: {}", lat, lng, e.getMessage());
            return null;
        }
    }

    // ── Internal ─────────────────────────────────────────────

    private JsonNode fetchWithRetry(String baseUrl) {
        final String requestId = UUID.randomUUID().toString().substring(0, 8);
        final String sanitizedBaseUrl = sanitizeUrl(baseUrl);
        final Instant requestStart = Instant.now();

        if (verboseSyncDebug) {
            log.info("[OpenMeteo][{}] request-start baseUrl={}", requestId, sanitizedBaseUrl);
        }

        return circuitBreaker.execute(SERVICE, () -> {
            int maxRetries = 2;
            long delay = 500;

            for (int attempt = 0;; attempt++) {
                Instant attemptStart = Instant.now();
                try {
                    String url = appendApiKey(baseUrl);
                    String response = restTemplate.getForObject(URI.create(url), String.class);
                    JsonNode parsed = objectMapper.readTree(response);

                    if (verboseSyncDebug) {
                        log.info(
                                "[OpenMeteo][{}] request-success attempt={} elapsedMs={} totalElapsedMs={} responseShape={}",
                                requestId,
                                attempt + 1,
                                Duration.between(attemptStart, Instant.now()).toMillis(),
                                Duration.between(requestStart, Instant.now()).toMillis(),
                                parsed.isArray() ? "ARRAY" : "OBJECT");
                    }

                    return parsed;
                } catch (HttpClientErrorException e) {
                    int status = e.getStatusCode().value();
                    String responseSnippet = e.getResponseBodyAsString();
                    if (responseSnippet != null && responseSnippet.length() > 220) {
                        responseSnippet = responseSnippet.substring(0, 220) + "...";
                    }

                    log.warn(
                            "[OpenMeteo][{}] request-http-error attempt={} status={} retriable={} elapsedMs={} responseSnippet={}",
                            requestId,
                            attempt + 1,
                            status,
                            (status == 429 || status >= 500),
                            Duration.between(attemptStart, Instant.now()).toMillis(),
                            responseSnippet);

                    if (apiKeyManager.isRateLimitError(status)) {
                        String key = apiKeyManager.getNextKey(SERVICE);
                        if (key != null) {
                            apiKeyManager.blockKey(SERVICE, key, 3_600_000L);
                        }
                    }

                    boolean retriable = status == 429 || status >= 500;
                    if (attempt >= maxRetries || !retriable) {
                        throw e;
                    }

                    sleep(delay);
                    delay *= 2;
                } catch (Exception e) {
                    log.warn(
                            "[OpenMeteo][{}] request-exception attempt={} elapsedMs={} message={}",
                            requestId,
                            attempt + 1,
                            Duration.between(attemptStart, Instant.now()).toMillis(),
                            e.getMessage());

                    if (attempt >= maxRetries)
                        throw new RuntimeException(e);
                    sleep(delay);
                    delay *= 2;
                }
            }
        });
    }

    private String appendApiKey(String url) {
        String key = apiKeyManager.getNextKey(SERVICE);
        if (key != null) {
            String separator = url.contains("?") ? "&" : "?";
            return url + separator + "apikey=" + key;
        }
        if (verboseSyncDebug) {
            log.warn("[OpenMeteo] No API key available for {}, sending request without api key", SERVICE);
        }
        return url;
    }

    private String sanitizeUrl(String url) {
        return url.replaceAll("([?&]apikey=)[^&]+", "$1***");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
