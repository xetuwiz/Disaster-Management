package com.sidms.backend.entity;

import com.sidms.backend.entity.enums.SpatialType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "spatial_units")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpatialUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid DEFAULT uuid_generate_v4()")
    private UUID id;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(name = "name_sinhala", length = 255)
    private String nameSinhala;

    @Column(name = "name_tamil", length = 255)
    private String nameTamil;

    @Column(length = 50, unique = true, nullable = false)
    private String pcode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "spatial_type")
    private SpatialType type;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column
    private Integer population;

    @Column(name = "is_tracked")
    private Boolean isTracked;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "elevation_m", columnDefinition = "numeric")
    private Double elevationM;

    public String getDisplayName(String language) {
        if ("si".equals(language) && nameSinhala != null) return nameSinhala;
        if ("ta".equals(language) && nameTamil != null) return nameTamil;
        return name;
    }
}
