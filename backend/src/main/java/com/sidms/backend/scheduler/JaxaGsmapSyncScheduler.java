package com.sidms.backend.scheduler;

import com.sidms.backend.client.JaxaGsmapClient;
import com.sidms.backend.service.SyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Hourly scheduler that ingests JAXA GSMaP satellite rainfall data for Sri
 * Lanka.
 *
 * GSMaP_NRT has a ~4-hour publication latency — this scheduler fetches
 * the hour that is 4 hours behind current UTC time.
 * The actual observation timestamp is derived from the downloaded filename,
 * not from the scheduler's target hour.
 *
 * Cron: 5 minutes past each hour — gives JAXA time to publish the file.
 * Cooldown: 1 hour — prevents double-downloads if triggered manually.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JaxaGsmapSyncScheduler {

    private static final String JOB_NAME = "jaxa_gsmap_sync";
    private static final Duration COOLDOWN = Duration.ofHours(1);

    private final JaxaGsmapClient jaxaGsmapClient;
    private final SyncStateService syncStateService;

    // =========================================================================
    // Scheduled entry point
    // =========================================================================

    /** Fires at HH:05:00 every hour. */
    @Scheduled(cron = "0 5 * * * *")
    public void scheduledRun() {
        runWithStateTracking();
    }

    public void runWithStateTracking() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN))
            return;
        try {
            doSync();
            syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
        } catch (Exception e) {
            log.error("[{}] Sync failed: {}", JOB_NAME, e.getMessage(), e);
            syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
        }
    }

    // =========================================================================
    // Public sync — callable from AdminSyncController for manual override
    // =========================================================================

    public void doSync() {
        // GSMaP_NRT has ~4h publication latency — fetch 4 hours behind
        LocalDateTime targetHour = LocalDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS)
                .minusHours(4);

        log.info("[JAXA] Sync started — fetching grid for {}", targetHour);
        jaxaGsmapClient.fetchAndStore(targetHour);
        log.info("[JAXA] Stored Sri Lanka rainfall grid for {}", targetHour);
    }
}
