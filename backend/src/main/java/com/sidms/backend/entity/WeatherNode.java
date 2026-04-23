package com.sidms.backend.entity;

import com.sidms.backend.entity.enums.WeatherNodeDensity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "weather_nodes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherNode {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid DEFAULT uuid_generate_v4()")
    private UUID id;

    @Column(length = 50, unique = true, nullable = false)
    private String code;

    @Column(name = "grid_key", length = 50, unique = true, nullable = false)
    private String gridKey;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    @Column(name = "elevation_m", columnDefinition = "numeric")
    private Integer elevationM;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "zone_density", columnDefinition = "weather_node_density")
    private WeatherNodeDensity zoneDensity;

    @Column(name = "is_coastal")
    private Boolean isCoastal;

    @Column(name = "is_mountain")
    private Boolean isMountain;

    @Column(name = "distance_to_coast_km")
    private Double distanceToCoastKm;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "is_volatile")
    private Boolean isVolatile;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "bias_temp_c", columnDefinition = "numeric")
    private Double biasTempC;

    @Column(name = "bias_humidity_pct", columnDefinition = "numeric")
    private Double biasHumidityPct;

    @Column(name = "bias_wind_ms", columnDefinition = "numeric")
    private Double biasWindMs;
}
