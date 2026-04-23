package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "station_metadata")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StationMetadata {

    @Id
    @Column(name = "station_id", length = 20)
    private String stationId;

    @Column(name = "station_name", length = 100, nullable = false)
    private String stationName;

    @Column(name = "station_type", length = 20, nullable = false)
    private String stationType;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "elevation_m", precision = 6, scale = 1)
    private BigDecimal elevationM;
}
