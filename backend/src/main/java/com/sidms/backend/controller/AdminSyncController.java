package com.sidms.backend.controller;

import com.sidms.backend.entity.SyncState;
import com.sidms.backend.exception.ResourceNotFoundException;
import com.sidms.backend.scheduler.AlertRuleEvaluator;
import com.sidms.backend.scheduler.BiasRecalculationScheduler;
import com.sidms.backend.scheduler.CacheWarmingScheduler;
import com.sidms.backend.scheduler.FloodSyncScheduler;
import com.sidms.backend.scheduler.HistoricalBackfillScheduler;
import com.sidms.backend.scheduler.JaxaGsmapSyncScheduler;
import com.sidms.backend.scheduler.MeteoStationSyncScheduler;
import com.sidms.backend.scheduler.MeteoSyncScheduler;
import com.sidms.backend.scheduler.NoaaMetarSyncScheduler;
import com.sidms.backend.scheduler.WeatherSyncScheduler;
import com.sidms.backend.scheduler.YrNoSyncScheduler;
import com.sidms.backend.scheduler.MetCelestialSyncScheduler;
import com.sidms.backend.service.SyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin-only REST controller that exposes manual sync triggers and status
 * for every scheduled job in the ClimaSphere weather engine.
 *
 * Endpoints:
 * GET /api/v1/admin/sync/status — list all SyncState rows
 * POST /api/v1/admin/sync/run/{jobName} — force a single job now
 * POST /api/v1/admin/sync/reset-cooldown/{jobName} — reset a job's cooldown
 * timer
 *
 * All endpoints require ROLE_ADMIN or ROLE_admin.
 */
@RestController
@RequestMapping("/api/v1/admin/sync")
@RequiredArgsConstructor
@Slf4j
public class AdminSyncController {

    private final SyncStateService syncStateService;

    // ── Weather engine schedulers ─────────────────────────────────────────────
    private final YrNoSyncScheduler yrNoSyncScheduler;
    private final MeteoStationSyncScheduler meteoStationSyncScheduler;
    private final NoaaMetarSyncScheduler noaaMetarSyncScheduler;
    private final JaxaGsmapSyncScheduler jaxaGsmapSyncScheduler;
    private final BiasRecalculationScheduler biasRecalculationScheduler;
    private final MetCelestialSyncScheduler metCelestialSyncScheduler;

    // ── Core platform schedulers ──────────────────────────────────────────────
    private final WeatherSyncScheduler weatherSyncScheduler;
    private final FloodSyncScheduler floodSyncScheduler;
    private final MeteoSyncScheduler meteoSyncScheduler;
    private final CacheWarmingScheduler cacheWarmingScheduler;
    private final HistoricalBackfillScheduler historicalBackfillScheduler;
    private final AlertRuleEvaluator alertRuleEvaluator;

    // =========================================================================
    // GET /status — dashboard overview of all job states
    // =========================================================================

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN') or hasRole('admin')")
    public ResponseEntity<List<SyncState>> getStatus() {
        return ResponseEntity.ok(syncStateService.getAllStates());
    }

    // =========================================================================
    // POST /run/{jobName} — trigger a job immediately (sets override, then runs)
    // =========================================================================

    @PostMapping("/run/{jobName}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('admin')")
    public ResponseEntity<Map<String, Object>> runJob(@PathVariable String jobName) {
        log.info("[AdminSyncController] Manual trigger requested for job: {}", jobName);

        // Set manual override so shouldRun() returns true on next call
        syncStateService.setManualOverride(jobName, true);

        switch (jobName) {
            case "yrno_sync" -> yrNoSyncScheduler.scheduledRun();
            case "openmeteo_forecast_sync" -> weatherSyncScheduler.scheduledSyncWeatherForecasts();
            case "meteo_gov_lk_sync" -> meteoStationSyncScheduler.scheduledRun();
            case "noaa_metar_sync" -> noaaMetarSyncScheduler.scheduledRun();
            case "jaxa_gsmap_sync" -> jaxaGsmapSyncScheduler.runWithStateTracking();
            case "bias_recalc_sync" -> biasRecalculationScheduler.scheduledRun();
            case "weather_sync" -> yrNoSyncScheduler.scheduledRun();
            case "met_celestial_sync" -> metCelestialSyncScheduler.scheduledSyncCelestialData();
            case "flood_sync" -> floodSyncScheduler.runWithStateTracking();
            case "meteo_content_sync" -> meteoSyncScheduler.syncMeteoContent();
            case "cache_warming" -> cacheWarmingScheduler.scheduledWarmCaches();
            case "historical_backfill" -> historicalBackfillScheduler.scheduledBackfill();
            case "alert_evaluation" -> alertRuleEvaluator.scheduledEvaluateAlertRules();
            default -> throw new ResourceNotFoundException("Unknown job: " + jobName);
        }

        log.info("[AdminSyncController] Job {} triggered successfully", jobName);
        return ResponseEntity.ok(Map.of(
                "triggered", jobName,
                "at", LocalDateTime.now().toString()));
    }

    // =========================================================================
    // POST /reset-cooldown/{jobName} — reset cooldown without triggering the job
    // =========================================================================

    @PostMapping("/reset-cooldown/{jobName}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('admin')")
    public ResponseEntity<Map<String, Object>> resetCooldown(@PathVariable String jobName) {
        log.info("[AdminSyncController] Cooldown reset requested for job: {}", jobName);
        syncStateService.resetCooldown(jobName);
        return ResponseEntity.ok(Map.of("reset", jobName));
    }
}
