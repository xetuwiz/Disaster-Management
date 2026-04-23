package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "jaxa_rain_grid")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JaxaRainGrid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "timestamp_utc", nullable = false)
    private LocalDateTime timestampUtc;

    @Column(name = "grid_lat", nullable = false, precision = 8, scale = 6)
    private BigDecimal gridLat;

    @Column(name = "grid_lon", nullable = false, precision = 9, scale = 6)
    private BigDecimal gridLon;

    @Column(name = "rainfall_mm", nullable = false, precision = 7, scale = 3)
    private BigDecimal rainfallMm;

    @Column(name = "product_type", length = 30)
    private String productType;
}
