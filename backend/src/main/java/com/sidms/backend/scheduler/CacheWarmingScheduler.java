package com.sidms.backend.scheduler;

import com.sidms.backend.client.MeteoSLClient;
import com.sidms.backend.service.DisasterWarningService;
import com.sidms.backend.service.FloodService;
import com.sidms.backend.service.MeteoScraperService;
import com.sidms.backend.service.SyncStateService;
import com.sidms.backend.service.WeatherService;
import com.sidms.backend.entity.SpatialUnit;
import com.sidms.backend.entity.enums.SpatialType;
import com.sidms.backend.repository.SpatialUnitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class CacheWarmingScheduler {

    private static final String   JOB_NAME = "cache_warming";
    private static final Duration COOLDOWN  = Duration.ofMinutes(15);

    private final SyncStateService        syncStateService;
    private final DisasterWarningService  disasterWarningService;
    private final FloodService            floodService;
    private final MeteoScraperService     meteoScraperService;
    private final MeteoSLClient           meteoSLClient;
    private final SpatialUnitRepository   spatialUnitRepository;
    private final WeatherService          weatherService;

    // ──────────────────────────────────────────────
    // Cache warming: every 15 min (initial delay 10s)
    // ──────────────────────────────────────────────
    @Scheduled(fixedDelayString = "${app.sync.warming.interval}", initialDelayString = "${app.sync.warming.initial-delay}")
    public void scheduledWarmCaches() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN)) return;
        try {
            warmCaches();
            syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
        } catch (Exception e) {
            log.error("[{}] Sync failed: {}", JOB_NAME, e.getMessage(), e);
            syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
        }
    }

    /** Public entry point — also called by AdminSyncController for manual triggers. */
    public void warmCaches() {
        log.info("⏳ Cache warming started");
        long start = System.currentTimeMillis();

        // 1. Warm warnings:active cache
        try {
            disasterWarningService.getAllActiveWarnings();
            log.debug("Warmed warnings:active cache");
        } catch (Exception e) {
            log.warn("Failed to warm warnings cache: {}", e.getMessage());
        }

        // 2. Warm flood:dashboard cache
        try {
            floodService.getDashboard();
            log.debug("Warmed flood:dashboard cache");
        } catch (Exception e) {
            log.warn("Failed to warm flood dashboard cache: {}", e.getMessage());
        }

        // 3. Warm meteo:content cache (original bulletins)
        try {
            meteoScraperService.getLatestBulletins();
            log.debug("Warmed meteo:content cache");
        } catch (Exception e) {
            log.warn("Failed to warm meteo content cache: {}", e.getMessage());
        }

        // 4. Warm MeteoSL forecast & advisory caches (NEW)
        try {
            meteoSLClient.fetchContentJson();
            meteoSLClient.getPublicForecast();
            meteoSLClient.getAdvisories();
            log.debug("Warmed MeteoSL forecast/advisory caches");
        } catch (Exception e) {
            log.warn("Failed to warm MeteoSL caches: {}", e.getMessage());
        }

        // 5. Warm ArcGIS station/gauge caches (NEW)
        try {
            floodService.getArcGISAlertSummary();
            log.debug("Warmed ArcGIS gauge caches");
        } catch (Exception e) {
            log.warn("Failed to warm ArcGIS caches: {}", e.getMessage());
        }

        // 6. Warm top district weather caches (NEW — matches reference cacheWarmer)
        try {
            List<SpatialUnit> districts = spatialUnitRepository.findByType(SpatialType.DISTRICT);
            int warmed = 0;
            for (SpatialUnit district : districts) {
                try {
                    weatherService.getWeatherForSpatialUnit(district.getId());
                    warmed++;
                } catch (Exception e) {
                    // Non-critical — may not have IDW mappings for all districts
                }
            }
            log.debug("Warmed weather cache for {}/{} districts", warmed, districts.size());
        } catch (Exception e) {
            log.warn("Failed to warm district weather caches: {}", e.getMessage());
        }

        log.info("✅ Cache warming completed in {}ms", System.currentTimeMillis() - start);
    }
}
