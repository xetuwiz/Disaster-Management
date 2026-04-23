package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "gn_station_anchors")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GnStationAnchor {

    @EmbeddedId
    private GnStationAnchorId id;

    @Column(name = "distance_km", precision = 5, scale = 2)
    private BigDecimal distanceKm;
}
