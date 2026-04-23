package com.sidms.backend.repository;

import com.sidms.backend.entity.JaxaRainGrid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface JaxaRainGridRepository extends JpaRepository<JaxaRainGrid, Long> {

    Optional<JaxaRainGrid> findTopByGridLatBetweenAndGridLonBetweenAndTimestampUtcAfterOrderByTimestampUtcDesc(
            double latLow, double latHigh,
            double lonLow, double lonHigh,
            LocalDateTime since);
}
