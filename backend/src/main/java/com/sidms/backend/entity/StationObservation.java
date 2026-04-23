package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "station_observations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StationObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", length = 20, nullable = false)
    private String stationId;

    @Column(name = "timestamp_utc", nullable = false)
    private LocalDateTime timestampUtc;

    @Column(name = "temperature_c", precision = 5, scale = 2)
    private BigDecimal temperatureC;

    @Column(name = "rainfall_mm", precision = 6, scale = 2)
    private BigDecimal rainfallMm;

    @Column(name = "humidity_pct", precision = 5, scale = 2)
    private BigDecimal humidityPct;

    @Column(name = "wind_speed_ms", precision = 5, scale = 2)
    private BigDecimal windSpeedMs;

    @Column(name = "weather_type", length = 50)
    private String weatherType;

    @Column(name = "source", length = 20, nullable = false)
    private String source;

    @Column(name = "raw_data", columnDefinition = "TEXT")
    private String rawData;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
