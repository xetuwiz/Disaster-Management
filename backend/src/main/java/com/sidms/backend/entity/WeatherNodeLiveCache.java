package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "weather_node_live_cache")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherNodeLiveCache {

    @Id
    @Column(name = "weather_node_id")
    private UUID weatherNodeId;

    @Column(name = "source_api", length = 50)
    private String sourceApi;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @Column(name = "temp_c")
    private Double tempC;

    @Column(name = "apparent_temp_c")
    private Double apparentTempC;

    @Column(name = "humidity_pct")
    private Double humidityPct;

    @Column(name = "pressure_hpa")
    private Double pressureHpa;

    @Column(name = "precipitation_mm")
    private Double precipitationMm;

    @Column(name = "precip_probability")
    private Double precipProbability;

    @Column(name = "rain_mm")
    private Double rainMm;

    @Column(name = "wind_speed_kmh")
    private Double windSpeedKmh;

    @Column(name = "wind_gust_kmh")
    private Double windGustKmh;

    @Column(name = "wind_direction_deg")
    private Double windDirectionDeg;

    @Column(name = "cloud_cover_pct")
    private Double cloudCoverPct;

    @Column(name = "visibility_m")
    private Double visibilityM;

    @Column(name = "uv_index")
    private Double uvIndex;

    @Column(name = "cape_jkg")
    private Double capeJkg;

    @Column(name = "weather_code")
    private Integer weatherCode;

    @Column(name = "symbol_code", length = 50)
    private String symbolCode;

    @Column(name = "us_aqi")
    private Double usAqi;

    @Column(name = "pm10")
    private Double pm10;

    @Column(name = "pm2_5")
    private Double pm25;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
