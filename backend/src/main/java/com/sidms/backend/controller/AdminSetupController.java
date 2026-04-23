package com.sidms.backend.controller;

import com.sidms.backend.dto.admin.SchedulerWorkerStatusDto;
import com.sidms.backend.scheduler.HistoricalBackfillScheduler;
import com.sidms.backend.service.IdwComputationService;
import com.sidms.backend.service.SchedulerAdminService;
import com.sidms.backend.service.SpatialImportService;
import com.sidms.backend.service.WeatherNodeGeneratorService;
import com.sidms.backend.scheduler.WeatherSyncScheduler;
import com.sidms.backend.scheduler.MeteoSyncScheduler;
import com.sidms.backend.scheduler.FloodSyncScheduler;
import com.sidms.backend.scheduler.CacheWarmingScheduler;
import com.sidms.backend.scheduler.AlertRuleEvaluator;
import com.sidms.backend.scheduler.ArcGisSyncScheduler;
import com.sidms.backend.scheduler.MetCelestialSyncScheduler;
import com.sidms.backend.scheduler.YrNoSyncScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/v1/admin/setup")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class AdminSetupController {

    private final Set<String> runningWorkers = ConcurrentHashMap.newKeySet();

    private final SpatialImportService spatialImportService;
    private final WeatherNodeGeneratorService weatherNodeGeneratorService;
    private final IdwComputationService idwComputationService;
    private final HistoricalBackfillScheduler historicalBackfillScheduler;
    private final WeatherSyncScheduler weatherSyncScheduler;
    private final MeteoSyncScheduler meteoSyncScheduler;
    private final FloodSyncScheduler floodSyncScheduler;
    private final CacheWarmingScheduler cacheWarmingScheduler;
    private final AlertRuleEvaluator alertRuleEvaluator;
    private final ArcGisSyncScheduler arcGisSyncScheduler;
    private final SchedulerAdminService schedulerAdminService;
    private final MetCelestialSyncScheduler metCelestialSyncScheduler;
    private final YrNoSyncScheduler yrNoSyncScheduler;

    // ──────────────────────────────────────────────
    // Spatial import
    // ──────────────────────────────────────────────

    @PostMapping("/import-spatial")
    public ResponseEntity<Map<String, Object>> importSpatial() {
        if (spatialImportService.isAlreadyImported()) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_IMPORTED",
                    "counts", spatialImportService.getImportStatus()));
        }

        // Run async so the HTTP request returns immediately
        CompletableFuture.runAsync(() -> {
            try {
                spatialImportService.importAll();
            } catch (Exception e) {
                log.error("Async spatial import failed: {}", e.getMessage());
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "IMPORT_STARTED",
                "message", "Spatial unit import started in background. Poll /import-status for progress."));
    }

    @GetMapping("/import-status")
    public ResponseEntity<Map<String, Object>> importStatus() {
        Map<String, Long> counts = spatialImportService.getImportStatus();
        boolean imported = spatialImportService.isAlreadyImported();
        return ResponseEntity.ok(Map.of(
                "imported", imported,
                "counts", counts));
    }

    // ──────────────────────────────────────────────
    // Weather node generation
    // ──────────────────────────────────────────────

    @PostMapping("/generate-nodes")
    public ResponseEntity<Map<String, Object>> generateNodes() {
        if (weatherNodeGeneratorService.isAlreadyGenerated()) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_GENERATED",
                    "message", "Weather nodes already exist."));
        }

        CompletableFuture.runAsync(() -> {
            try {
                weatherNodeGeneratorService.generateNodes();
            } catch (Exception e) {
                log.error("Async weather node generation failed: {}", e.getMessage());
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "GENERATION_STARTED",
                "message", "Weather node generation started in background."));
    }

    // ──────────────────────────────────────────────
    // IDW computation
    // ──────────────────────────────────────────────

    @PostMapping("/compute-idw")
    public ResponseEntity<Map<String, Object>> computeIdw() {
        Map<String, Object> status = idwComputationService.getStatus();
        if ("COMPUTING".equals(status.get("status"))) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_RUNNING",
                    "progress", status));
        }

        idwComputationService.computeAll();

        return ResponseEntity.accepted().body(Map.of(
                "status", "IDW_COMPUTATION_STARTED",
                "message", "IDW computation started in background. Poll /idw-status for progress."));
    }

    @GetMapping("/idw-status")
    public ResponseEntity<Map<String, Object>> idwStatus() {
        return ResponseEntity.ok(idwComputationService.getStatus());
    }

    // ──────────────────────────────────────────────
    // Historical backfill
    // ──────────────────────────────────────────────

    @PostMapping("/backfill-history")
    public ResponseEntity<Map<String, Object>> backfillHistory(
            @RequestParam(defaultValue = "730") int days) {
        CompletableFuture.runAsync(() -> {
            try {
                historicalBackfillScheduler.backfillLastNDays(days);
            } catch (Exception e) {
                log.error("Async historical backfill failed: {}", e.getMessage());
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "BACKFILL_STARTED",
                "days", days,
                "message", "Historical backfill started for the last " + days + " days."));
    }

    // ──────────────────────────────────────────────
    // Manual Syncs
    // ──────────────────────────────────────────────

    @PostMapping("/sync-weather")
    public ResponseEntity<Map<String, Object>> syncWeather() {
        CompletableFuture.runAsync(() -> {
            try {
                yrNoSyncScheduler.doSync();
            } catch (Exception e) {
                log.error("Async weather sync failed: {}", e.getMessage());
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "SYNC_STARTED",
                "message", "Weather sync started in background."));
    }

    @PostMapping("/sync-forecasts")
    public ResponseEntity<Map<String, Object>> syncForecasts() {
        CompletableFuture.runAsync(() -> {
            try {
                weatherSyncScheduler.syncWeatherForecasts();
            } catch (Exception e) {
                log.error("Async weather forecasts sync failed: {}", e.getMessage());
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "SYNC_STARTED",
                "message", "Weather Daily Forecasts sync started in background."));
    }

    @PostMapping("/evict-cache")
    public ResponseEntity<Map<String, Object>> evictOldCache() {
        CompletableFuture.runAsync(() -> {
            try {
                weatherSyncScheduler.evictWeatherCaches();
            } catch (Exception e) {
                log.error("Async cache eviction failed: {}", e.getMessage());
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "EVICT_STARTED",
                "message", "Entity cache eviction started in background."));
    }

    @PostMapping("/sync-meteo")
    public ResponseEntity<Map<String, Object>> syncMeteo() {
        CompletableFuture.runAsync(() -> {
            try {
                meteoSyncScheduler.syncMeteoContent();
            } catch (Exception e) {
                log.error("Async meteo sync failed: {}", e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "status", "SYNC_STARTED",
                "message", "Meteo bulletins sync started in background."));
    }

    @PostMapping("/sync-flood")
    public ResponseEntity<Map<String, Object>> syncFlood() {
        CompletableFuture.runAsync(() -> {
            try {
                floodSyncScheduler.syncFloodGauges();
            } catch (Exception e) {
                log.error("Async flood sync failed: {}", e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "status", "SYNC_STARTED",
                "message", "Irrigation Dept flood sync started in background."));
    }

    @PostMapping("/sync-rivernet")
    public ResponseEntity<Map<String, Object>> syncRivernet() {
        CompletableFuture.runAsync(() -> {
            try {
                floodSyncScheduler.syncRivernetDevices();
            } catch (Exception e) {
                log.error("Async rivernet sync failed: {}", e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "status", "SYNC_STARTED",
                "message", "Rivernet sensors sync started in background."));
    }

    @PostMapping("/sync-arcgis")
    public ResponseEntity<Map<String, Object>> syncArcGis() {
        CompletableFuture.runAsync(() -> {
            try {
                arcGisSyncScheduler.scrapeArcGisData();
            } catch (Exception e) {
                log.error("Async ArcGIS sensor sync failed: {}", e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "status", "SYNC_STARTED",
                "message", "ArcGIS sensor data scrape started in background."));
    }

    @PostMapping("/warm-cache")
    public ResponseEntity<Map<String, Object>> warmCache() {
        CompletableFuture.runAsync(() -> {
            try {
                cacheWarmingScheduler.warmCaches();
            } catch (Exception e) {
                log.error("Async cache warming failed: {}", e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "status", "WARM_STARTED",
                "message", "Cache warming started in background."));
    }

    @PostMapping("/evaluate-alerts")
    public ResponseEntity<Map<String, Object>> evaluateAlerts() {
        CompletableFuture.runAsync(() -> {
            try {
                alertRuleEvaluator.evaluateAlertRules();
            } catch (Exception e) {
                log.error("Async alert evaluation failed: {}", e.getMessage());
            }
        });
        return ResponseEntity.accepted().body(Map.of(
                "status", "EVAL_STARTED",
                "message", "Alert rules evaluation started in background."));
    }

    @GetMapping("/workers/status")
    public ResponseEntity<List<SchedulerWorkerStatusDto>> getWorkerStatuses() {
        return ResponseEntity.ok(schedulerAdminService.getWorkerStatuses());
    }

    @PostMapping("/workers/run/{workerKey}")
    public ResponseEntity<Map<String, Object>> runWorkerNow(
            @PathVariable String workerKey,
            @RequestParam(required = false, defaultValue = "730") Integer days) {
        String normalized = workerKey == null ? "" : workerKey.trim().toLowerCase();

        if (!runningWorkers.add(normalized)) {
            return ResponseEntity.status(409).body(Map.of(
                    "status", "ALREADY_RUNNING",
                    "workerKey", normalized,
                    "message", "Worker is already running."));
        }

        Runnable task;
        switch (normalized) {
            case "import-spatial" -> task = spatialImportService::importAll;
            case "generate-nodes" -> task = weatherNodeGeneratorService::generateNodes;
            case "compute-idw" -> task = idwComputationService::computeAll;
            case "backfill-history" -> task = () -> historicalBackfillScheduler.backfillLastNDays(days);
            case "sync-weather" -> task = yrNoSyncScheduler::doSync;
            case "sync-celestial" -> task = metCelestialSyncScheduler::syncCelestialData;
            case "sync-forecasts" -> task = weatherSyncScheduler::syncWeatherForecasts;
            case "evict-cache" -> task = weatherSyncScheduler::evictWeatherCaches;
            case "sync-meteo" -> task = meteoSyncScheduler::syncMeteoContent;
            case "sync-flood"    -> task = floodSyncScheduler::syncFloodGauges;
            case "sync-rivernet" -> task = floodSyncScheduler::syncRivernetDevices;
            case "sync-arcgis"   -> task = arcGisSyncScheduler::scrapeArcGisData;
            case "warm-cache"    -> task = cacheWarmingScheduler::warmCaches;
            case "evaluate-alerts" -> task = alertRuleEvaluator::evaluateAlertRules;
            default -> {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "UNKNOWN_WORKER",
                        "workerKey", workerKey,
                        "message", "Unsupported worker key."));
            }
        }

        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Manual worker run failed ({}): {}", normalized, e.getMessage());
            } finally {
                runningWorkers.remove(normalized);
            }
        });

        return ResponseEntity.accepted().body(Map.of(
                "status", "RUN_STARTED",
                "workerKey", normalized,
                "message", "Worker run started in background."));
    }
}
