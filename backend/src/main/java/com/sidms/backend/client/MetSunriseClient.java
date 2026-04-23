package com.sidms.backend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidms.backend.util.ApiCircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
@Slf4j
public class MetSunriseClient {

    private static final String SERVICE = "MET_SUNRISE";
    private static final String BASE_URL_SUN = "https://api.met.no/weatherapi/sunrise/3.0/sun";
    private static final String BASE_URL_MOON = "https://api.met.no/weatherapi/sunrise/3.0/moon";
    private static final String USER_AGENT = "ClimaSphere/1.0 (admin@climasphere.lk)";

    private static final int MAX_RETRIES = 3;
    private static final long[] NETWORK_DELAYS = {2_000L, 4_000L, 8_000L};
    private static final long RATE_LIMIT_MIN = 30_000L;
    private static final long RATE_LIMIT_MAX = 40_000L;

    private final RestTemplate restTemplate;
    private final ApiCircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;

    public MetSunriseClient(RestTemplate restTemplate,
                            ApiCircuitBreaker circuitBreaker,
                            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;
    }

    public JsonNode getSunEvents(double lat, double lng, LocalDate date, String offset) {
        log.info("[MET_SUNRISE] sun-request-start lat={} lon={} date={}", lat, lng, date);
        String url = String.format("%s?lat=%f&lon=%f&date=%s", 
            BASE_URL_SUN, lat, lng, date.format(DateTimeFormatter.ISO_LOCAL_DATE));
        if (offset != null && !offset.isBlank()) {
            url += "&offset=" + offset;
        }
        return fetchWithRetry(url);
    }

    public JsonNode getMoonEvents(double lat, double lng, LocalDate date, String offset) {
        log.info("[MET_SUNRISE] moon-request-start lat={} lon={} date={}", lat, lng, date);
        String url = String.format("%s?lat=%f&lon=%f&date=%s", 
            BASE_URL_MOON, lat, lng, date.format(DateTimeFormatter.ISO_LOCAL_DATE));
        if (offset != null && !offset.isBlank()) {
            url += "&offset=" + offset;
        }
        return fetchWithRetry(url);
    }

    private JsonNode fetchWithRetry(String url) {
        return circuitBreaker.execute(SERVICE, () -> {
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
                    HttpEntity<String> entity = new HttpEntity<>(headers);

                    ResponseEntity<String> response = restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            String.class
                    );

                    return objectMapper.readTree(response.getBody());

                } catch (HttpClientErrorException e) {
                    int status = e.getStatusCode().value();

                    if (status == 429) {
                        log.warn("[MET_SUNRISE] 429 Too Many Requests. Attempt {}/{}", attempt + 1, MAX_RETRIES + 1);
                        if (attempt == MAX_RETRIES) throw e;
                        long jitter = ThreadLocalRandom.current().nextLong(RATE_LIMIT_MIN, RATE_LIMIT_MAX);
                        sleep(jitter);
                    } else if (status >= 500) {
                        log.warn("[MET_SUNRISE] Server Error {}. Attempt {}/{}", status, attempt + 1, MAX_RETRIES + 1);
                        if (attempt == MAX_RETRIES) throw e;
                        sleep(NETWORK_DELAYS[attempt]);
                    } else {
                        log.error("[MET_SUNRISE] Fatal HTTP Error {}: {}", status, e.getResponseBodyAsString());
                        throw e;
                    }
                } catch (Exception e) {
                    log.warn("[MET_SUNRISE] Network Error. Attempt {}/{}", attempt + 1, MAX_RETRIES + 1, e);
                    if (attempt == MAX_RETRIES) throw new RuntimeException(e);
                    sleep(NETWORK_DELAYS[attempt]);
                }
            }
            throw new RuntimeException("Exhausted retries for " + url);
        });
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
