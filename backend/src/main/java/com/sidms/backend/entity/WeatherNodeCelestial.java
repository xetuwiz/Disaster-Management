package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "weather_node_celestial", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"weather_node_id", "record_date"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherNodeCelestial {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "weather_node_id", nullable = false)
    private UUID weatherNodeId;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    // Sun Events
    @Column(name = "sunrise_time")
    private OffsetDateTime sunriseTime;
    
    @Column(name = "sunrise_azimuth")
    private Double sunriseAzimuth;

    @Column(name = "sunset_time")
    private OffsetDateTime sunsetTime;

    @Column(name = "sunset_azimuth")
    private Double sunsetAzimuth;

    @Column(name = "solarnoon_time")
    private OffsetDateTime solarnoonTime;

    @Column(name = "solarnoon_elevation")
    private Double solarnoonElevation;

    @Column(name = "solarmidnight_time")
    private OffsetDateTime solarmidnightTime;

    @Column(name = "solarmidnight_elevation")
    private Double solarmidnightElevation;

    // Moon Events
    @Column(name = "moonrise_time")
    private OffsetDateTime moonriseTime;

    @Column(name = "moonrise_azimuth")
    private Double moonriseAzimuth;

    @Column(name = "moonset_time")
    private OffsetDateTime moonsetTime;

    @Column(name = "moonset_azimuth")
    private Double moonsetAzimuth;

    @Column(name = "high_moon_time")
    private OffsetDateTime highMoonTime;

    @Column(name = "high_moon_elevation")
    private Double highMoonElevation;

    @Column(name = "low_moon_time")
    private OffsetDateTime lowMoonTime;

    @Column(name = "low_moon_elevation")
    private Double lowMoonElevation;

    @Column(name = "moonphase")
    private Double moonphase;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
