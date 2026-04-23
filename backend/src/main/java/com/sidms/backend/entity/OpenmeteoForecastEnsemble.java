package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "openmeteo_forecast_ensemble")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenmeteoForecastEnsemble {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "forecast_date", nullable = false)
    private LocalDate forecastDate;

    @Column(name = "temp_min_p10", precision = 5, scale = 2)
    private BigDecimal tempMinP10;

    @Column(name = "temp_min_p50", precision = 5, scale = 2)
    private BigDecimal tempMinP50;

    @Column(name = "temp_min_p90", precision = 5, scale = 2)
    private BigDecimal tempMinP90;

    @Column(name = "temp_max_p10", precision = 5, scale = 2)
    private BigDecimal tempMaxP10;

    @Column(name = "temp_max_p50", precision = 5, scale = 2)
    private BigDecimal tempMaxP50;

    @Column(name = "temp_max_p90", precision = 5, scale = 2)
    private BigDecimal tempMaxP90;

    @Column(name = "precipitation_probability", precision = 5, scale = 4)
    private BigDecimal precipitationProbability;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
