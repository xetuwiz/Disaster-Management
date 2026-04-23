package com.sidms.backend.scheduler;

import com.sidms.backend.service.MeteoScraperService;
import com.sidms.backend.service.SyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Slf4j
@RequiredArgsConstructor
public class MeteoSyncScheduler {

    private static final String JOB_NAME = "meteo_content_sync";
    private static final Duration COOLDOWN = Duration.ofMinutes(15);

    private final MeteoScraperService meteoScraperService;
    private final StringRedisTemplate stringRedisTemplate;
    private final SyncStateService syncStateService;

    // ──────────────────────────────────────────────
    // Meteo content sync: every 15 minutes
    // ──────────────────────────────────────────────
    @Scheduled(fixedDelayString = "${app.sync.meteo.interval}", initialDelayString = "${app.sync.meteo.initial-delay}")
    public void syncMeteoContent() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN))
            return;
        log.info("⏳ Meteo content sync started");
        try {
            meteoScraperService.scrapeMeteoContent();

            // Evict meteo:content to force fresh load on next request
            try {
                stringRedisTemplate.delete("meteo:content");
            } catch (Exception e) {
                log.warn("Failed to evict meteo:content cache: {}", e.getMessage());
            }

            log.info("✅ Meteo content sync completed");
            syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
        } catch (Exception e) {
            log.error("Meteo content sync failed: {}", e.getMessage());
            syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
        }
    }
}
