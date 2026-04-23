package com.sidms.backend.repository;

import com.sidms.backend.entity.WeatherNodeCelestial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WeatherNodeCelestialRepository extends JpaRepository<WeatherNodeCelestial, UUID> {
    Optional<WeatherNodeCelestial> findByWeatherNodeIdAndRecordDate(UUID weatherNodeId, LocalDate recordDate);
}
