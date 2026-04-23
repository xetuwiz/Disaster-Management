package com.sidms.backend.repository;

import com.sidms.backend.entity.StationObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StationObservationRepository extends JpaRepository<StationObservation, Long> {

    Optional<StationObservation> findTopByStationIdOrderByTimestampUtcDesc(String stationId);

    List<StationObservation> findByTimestampUtcAfter(LocalDateTime since);

    boolean existsByStationIdAndTimestampUtc(String stationId, LocalDateTime timestampUtc);

    Optional<StationObservation> findTopByStationIdAndTimestampUtcAfterOrderByTimestampUtcDesc(
            String stationId, LocalDateTime since);
}


