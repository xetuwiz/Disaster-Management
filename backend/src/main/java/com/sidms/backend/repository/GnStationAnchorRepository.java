package com.sidms.backend.repository;

import com.sidms.backend.entity.GnStationAnchor;
import com.sidms.backend.entity.GnStationAnchorId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface GnStationAnchorRepository extends JpaRepository<GnStationAnchor, GnStationAnchorId> {

    Optional<GnStationAnchor> findFirstByIdGnId(UUID gnId);
}
