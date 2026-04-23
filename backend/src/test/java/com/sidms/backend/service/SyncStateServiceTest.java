package com.sidms.backend.service;

import com.sidms.backend.entity.SyncState;
import com.sidms.backend.repository.SyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncStateService — cooldown + manual override engine")
class SyncStateServiceTest {

    private static final String JOB = "test_job";
    private static final Duration COOLDOWN = Duration.ofMinutes(30);

    @Mock
    private SyncStateRepository repository;

    @InjectMocks
    private SyncStateService service;

    // Captor reused across tests that verify what gets saved
    private final ArgumentCaptor<SyncState> stateCaptor = ArgumentCaptor.forClass(SyncState.class);

    // =========================================================================
    // shouldRun — cooldown gate
    // =========================================================================

    @Test
    @DisplayName("1. shouldRun returns false when within cooldown (next_allowed_utc in the future)")
    void shouldRun_withinCooldown_returnsFalse() {
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .manualOverride(false)
                .nextAllowedUtc(LocalDateTime.now().plusMinutes(15))
                .errorCount(0)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));

        boolean result = service.shouldRun(JOB, COOLDOWN);

        assertThat(result).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("2. shouldRun returns true when cooldown has expired (next_allowed_utc in the past)")
    void shouldRun_cooldownExpired_returnsTrue() {
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .manualOverride(false)
                .nextAllowedUtc(LocalDateTime.now().minusMinutes(5))
                .errorCount(0)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));

        boolean result = service.shouldRun(JOB, COOLDOWN);

        assertThat(result).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("3. shouldRun returns true AND clears manual_override when override flag is true")
    void shouldRun_manualOverride_returnsTrueAndClearsFlag() {
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .manualOverride(true)
                // still within cooldown — override must bypass this
                .nextAllowedUtc(LocalDateTime.now().plusHours(2))
                .errorCount(0)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = service.shouldRun(JOB, COOLDOWN);

        assertThat(result).isTrue();
        verify(repository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getManualOverride()).isFalse();
    }

    @Test
    @DisplayName("4. shouldRun returns true when job has never run (next_allowed_utc is null)")
    void shouldRun_neverRun_returnsTrue() {
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .manualOverride(false)
                .nextAllowedUtc(null)
                .errorCount(0)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));

        boolean result = service.shouldRun(JOB, COOLDOWN);

        assertThat(result).isTrue();
        verify(repository, never()).save(any());
    }

    // =========================================================================
    // recordFailure — exponential backoff
    // =========================================================================

    @Test
    @DisplayName("5. recordFailure sets next_allowed_utc to now + cooldown for first failure")
    void recordFailure_firstFailure_usesNormalCooldown() {
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .errorCount(0)
                .manualOverride(false)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        service.recordFailure(JOB, COOLDOWN, "timeout");
        LocalDateTime after = LocalDateTime.now();

        verify(repository).save(stateCaptor.capture());
        SyncState saved = stateCaptor.getValue();

        assertThat(saved.getErrorCount()).isEqualTo(1);
        // next_allowed_utc should be approximately now + 30 min (normal cooldown, no backoff)
        LocalDateTime expectedMin = before.plus(COOLDOWN);
        LocalDateTime expectedMax = after.plus(COOLDOWN);
        assertThat(saved.getNextAllowedUtc()).isBetween(expectedMin, expectedMax);
    }

    @Test
    @DisplayName("6. recordFailure doubles cooldown after 3rd consecutive failure (exponential backoff)")
    void recordFailure_thirdFailure_appliesExponentialBackoff() {
        // errors = 2 already → next call makes it 3, backoff = cooldown × 2^(3-2) = × 2
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .errorCount(2)
                .manualOverride(false)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        service.recordFailure(JOB, COOLDOWN, "connection refused");
        LocalDateTime after = LocalDateTime.now();

        verify(repository).save(stateCaptor.capture());
        SyncState saved = stateCaptor.getValue();

        assertThat(saved.getErrorCount()).isEqualTo(3);

        // Expected backoff: 30min × 2 = 60min
        Duration expectedBackoff = COOLDOWN.multipliedBy(2);
        LocalDateTime expectedMin = before.plus(expectedBackoff);
        LocalDateTime expectedMax = after.plus(expectedBackoff);
        assertThat(saved.getNextAllowedUtc()).isBetween(expectedMin, expectedMax);
    }

    @Test
    @DisplayName("7. recordFailure caps backoff at 24 hours regardless of error count")
    void recordFailure_highErrorCount_capsAt24Hours() {
        // errors = 10 already → multiplier would be 2^min(8,4)=16 × 30min = 480min = 8h < 24h
        // Use a cooldown of 2h: 2h × 16 = 32h > 24h → cap kicks in
        Duration longCooldown = Duration.ofHours(2);
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .errorCount(10)
                .manualOverride(false)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        service.recordFailure(JOB, longCooldown, "persistent failure");
        LocalDateTime after = LocalDateTime.now();

        verify(repository).save(stateCaptor.capture());
        SyncState saved = stateCaptor.getValue();

        // next_allowed_utc must be ≤ now + 24h
        assertThat(saved.getNextAllowedUtc()).isBetween(
                before.plusHours(24).minusSeconds(1),
                after.plusHours(24).plusSeconds(1));
    }

    @Test
    @DisplayName("7b. recordFailure error message is truncated at 500 characters")
    void recordFailure_longError_truncatedTo500Chars() {
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .errorCount(0)
                .manualOverride(false)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String longError = "x".repeat(800);
        service.recordFailure(JOB, COOLDOWN, longError);

        verify(repository).save(stateCaptor.capture());
        assertThat(stateCaptor.getValue().getLastError()).hasSize(500);
    }

    // =========================================================================
    // resetCooldown
    // =========================================================================

    @Test
    @DisplayName("8. resetCooldown sets next_allowed_utc to the past (immediately runnable)")
    void resetCooldown_setsNextAllowedToPast() {
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .nextAllowedUtc(LocalDateTime.now().plusHours(5))
                .errorCount(7)
                .manualOverride(false)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetCooldown(JOB);

        verify(repository).save(stateCaptor.capture());
        SyncState saved = stateCaptor.getValue();

        assertThat(saved.getNextAllowedUtc()).isBefore(LocalDateTime.now());
        assertThat(saved.getErrorCount()).isZero();
    }

    // =========================================================================
    // shouldRun — creates default state when job not yet in DB
    // =========================================================================

    @Test
    @DisplayName("shouldRun creates and uses default state when job is not found in DB")
    void shouldRun_jobNotFound_createsDefaultAndAllows() {
        when(repository.findById(JOB)).thenReturn(Optional.empty());

        // Default state has null nextAllowedUtc → should return true
        boolean result = service.shouldRun(JOB, COOLDOWN);

        assertThat(result).isTrue();
    }

    // =========================================================================
    // recordSuccess
    // =========================================================================

    @Test
    @DisplayName("recordSuccess resets error count and schedules next cooldown window")
    void recordSuccess_resetsErrorsAndSchedulesCooldown() {
        SyncState state = SyncState.builder()
                .jobName(JOB)
                .errorCount(5)
                .lastError("previous failure")
                .manualOverride(false)
                .build();

        when(repository.findById(JOB)).thenReturn(Optional.of(state));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        service.recordSuccess(JOB, COOLDOWN);
        LocalDateTime after = LocalDateTime.now();

        verify(repository).save(stateCaptor.capture());
        SyncState saved = stateCaptor.getValue();

        assertThat(saved.getErrorCount()).isZero();
        assertThat(saved.getLastError()).isNull();
        assertThat(saved.getLastSuccessUtc()).isBetween(before, after);
        assertThat(saved.getNextAllowedUtc()).isBetween(
                before.plus(COOLDOWN), after.plus(COOLDOWN));
    }
}
