package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "node_timeseries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeTimeseries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "valid_from_utc", nullable = false)
    private LocalDateTime validFromUtc;

    @Column(name = "forecast_hour", nullable = false)
    private Integer forecastHour;

    @Column(name = "temperature_c", precision = 5, scale = 2)
    private BigDecimal temperatureC;

    @Column(name = "humidity_pct", precision = 5, scale = 2)
    private BigDecimal humidityPct;

    @Column(name = "wind_speed_ms", precision = 5, scale = 2)
    private BigDecimal windSpeedMs;

    @Column(name = "wind_direction_deg")
    private Integer windDirectionDeg;

    @Column(name = "pressure_hpa", precision = 6, scale = 1)
    private BigDecimal pressureHpa;

    @Column(name = "precipitation_mm", precision = 6, scale = 2)
    private BigDecimal precipitationMm;

    @Column(name = "weather_code")
    private Integer weatherCode;

    @Column(name = "symbol_code", length = 50)
    private String symbolCode;

    @Column(name = "cloud_cover_pct", precision = 5, scale = 2)
    private BigDecimal cloudCoverPct;

    @Column(name = "cloud_cover_high_pct", precision = 5, scale = 2)
    private BigDecimal cloudCoverHighPct;

    @Column(name = "cloud_cover_low_pct", precision = 5, scale = 2)
    private BigDecimal cloudCoverLowPct;

    @Column(name = "cloud_cover_medium_pct", precision = 5, scale = 2)
    private BigDecimal cloudCoverMediumPct;

    @Column(name = "dew_point_c", precision = 5, scale = 2)
    private BigDecimal dewPointC;

    @Column(name = "fog_area_fraction_pct", precision = 5, scale = 2)
    private BigDecimal fogAreaFractionPct;

    @Column(name = "uv_index_clear_sky", precision = 5, scale = 2)
    private BigDecimal uvIndexClearSky;

    @Column(name = "wind_gust_ms", precision = 5, scale = 2)
    private BigDecimal windGustMs;

    @Column(name = "temp_percentile_10", precision = 5, scale = 2)
    private BigDecimal tempPercentile10;

    @Column(name = "temp_percentile_90", precision = 5, scale = 2)
    private BigDecimal tempPercentile90;

    @Column(name = "wind_percentile_10", precision = 5, scale = 2)
    private BigDecimal windPercentile10;

    @Column(name = "wind_percentile_90", precision = 5, scale = 2)
    private BigDecimal windPercentile90;

    @Column(name = "precip_prob_pct", precision = 5, scale = 2)
    private BigDecimal precipProbPct;

    @Column(name = "thunder_prob_pct", precision = 5, scale = 2)
    private BigDecimal thunderProbPct;

    @Column(name = "precip_min_mm", precision = 6, scale = 2)
    private BigDecimal precipMinMm;

    @Column(name = "precip_max_mm", precision = 6, scale = 2)
    private BigDecimal precipMaxMm;

    @Column(name = "temp_max_c", precision = 5, scale = 2)
    private BigDecimal tempMaxC;

    @Column(name = "temp_min_c", precision = 5, scale = 2)
    private BigDecimal tempMinC;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
