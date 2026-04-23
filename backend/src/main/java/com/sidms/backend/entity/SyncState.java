package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "sync_state")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncState {

    @Id
    @Column(name = "job_name", length = 100)
    private String jobName;

    @Column(name = "last_success_utc")
    private LocalDateTime lastSuccessUtc;

    @Column(name = "next_allowed_utc")
    private LocalDateTime nextAllowedUtc;

    @Column(name = "manual_override")
    private Boolean manualOverride;

    @Column(name = "error_count")
    private Integer errorCount;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
