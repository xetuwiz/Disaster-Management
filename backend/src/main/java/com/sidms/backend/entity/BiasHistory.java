package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "bias_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BiasHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "variable", length = 20, nullable = false)
    private String variable;

    @Column(name = "bias_value", nullable = false, precision = 8, scale = 4)
    private BigDecimal biasValue;

    @Column(name = "timestamp_utc", nullable = false)
    private LocalDateTime timestampUtc;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
