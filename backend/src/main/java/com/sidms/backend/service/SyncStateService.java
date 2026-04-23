package com.sidms.backend.service;

import com.sidms.backend.entity.SyncState;
import com.sidms.backend.repository.SyncStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class SyncStateService {

    private final SyncStateRepository repository;

    /**
     * Returns true if the job is allowed to run now.
     * Priority order:
     *   1. manual_override=true  → always run, clear flag
     *   2. next_allowed_utc=null → never run before, allow
     *   3. now > next_allowed_utc → cooldown expired, allow
     */
    public boolean shouldRun(String jobName, Duration normalCooldown) {
        SyncState state = repository.findById(jobName)
                .orElseGet(() -> createDefault(jobName));

        if (Boolean.TRUE.equals(state.getManualOverride())) {
            state.setManualOverride(false);
            repository.save(state);
            log.info("[SyncState] Manual override consumed for job: {}", jobName);
            return true;
        }

        if (state.getNextAllowedUtc() == null) return true;

        boolean allowed = LocalDateTime.now(ZoneOffset.UTC).isAfter(state.getNextAllowedUtc());
        if (!allowed) {
            log.debug("[SyncState] {} skipped — cooldown until {}", jobName, state.getNextAllowedUtc());
        }
        return allowed;
    }

    /**
     * Records a successful run — resets error count and schedules next cooldown window.
     */
    @Transactional
    public void recordSuccess(String jobName, Duration cooldown) {
        SyncState state = repository.findById(jobName)
                .orElseGet(() -> createDefault(jobName));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        state.setLastSuccessUtc(now);
        state.setNextAllowedUtc(now.plus(cooldown));
        state.setErrorCount(0);
        state.setLastError(null);
        state.setUpdatedAt(now);
        repository.save(state);
        log.info("[SyncState] {} succeeded — next allowed: {}", jobName, state.getNextAllowedUtc());
    }

    /**
     * Records a failed run.
     * Applies exponential backoff starting from the 3rd consecutive failure.
     * Backoff multiplier: 2^(min(errors-2, 4)) — capped at 24 hours.
     *
     * failures:  1,2 → normal cooldown
     * failures:  3   → cooldown × 2
     * failures:  4   → cooldown × 4
     * failures:  5   → cooldown × 8
     * failures:  6+  → cooldown × 16 (or 24h cap, whichever is less)
     */
    @Transactional
    public void recordFailure(String jobName, Duration cooldown, String error) {
        SyncState state = repository.findById(jobName)
                .orElseGet(() -> createDefault(jobName));

        int errors = state.getErrorCount() + 1;
        state.setErrorCount(errors);
        state.setLastError(error.length() > 500 ? error.substring(0, 500) : error);

        Duration backoff = errors >= 3
                ? cooldown.multipliedBy((long) Math.pow(2, Math.min(errors - 2, 4)))
                : cooldown;
        if (backoff.toHours() > 24) backoff = Duration.ofHours(24);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        state.setNextAllowedUtc(now.plus(backoff));
        state.setUpdatedAt(now);
        repository.save(state);

        log.warn("[SyncState] {} failed #{}, next allowed: {}", jobName, errors, state.getNextAllowedUtc());
    }

    /**
     * Sets the manual_override flag — causes shouldRun() to return true once
     * regardless of cooldown, then self-clears.
     */
    @Transactional
    public void setManualOverride(String jobName, boolean value) {
        SyncState state = repository.findById(jobName)
                .orElseGet(() -> createDefault(jobName));
        state.setManualOverride(value);
        state.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        repository.save(state);
        log.info("[SyncState] Manual override set to {} for job: {}", value, jobName);
    }

    /**
     * Forces a job to be immediately runnable by setting next_allowed_utc to 1 second ago.
     * Also resets error count.
     */
    @Transactional
    public void resetCooldown(String jobName) {
        SyncState state = repository.findById(jobName)
                .orElseGet(() -> createDefault(jobName));
        state.setNextAllowedUtc(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        state.setErrorCount(0);
        state.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        repository.save(state);
        log.info("[SyncState] Cooldown reset for job: {}", jobName);
    }

    /** Returns all known job states for the admin dashboard. */
    public List<SyncState> getAllStates() {
        return repository.findAll();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SyncState createDefault(String jobName) {
        return SyncState.builder()
                .jobName(jobName)
                .errorCount(0)
                .manualOverride(false)
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();
    }
}
