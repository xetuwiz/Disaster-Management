package com.sidms.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GnStationAnchorId implements Serializable {

    @Column(name = "gn_id", nullable = false)
    private UUID gnId;

    @Column(name = "station_id", length = 20, nullable = false)
    private String stationId;
}
