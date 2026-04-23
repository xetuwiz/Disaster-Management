package com.sidms.backend.repository;

import com.sidms.backend.entity.OpenmeteoForecastEnsemble;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OpenmeteoForecastEnsembleRepository extends JpaRepository<OpenmeteoForecastEnsemble, Long> {

    Optional<OpenmeteoForecastEnsemble> findByNodeIdAndForecastDate(UUID nodeId, LocalDate forecastDate);

    List<OpenmeteoForecastEnsemble> findByNodeIdAndForecastDateBetweenOrderByForecastDateAsc(
            UUID nodeId, LocalDate fromDate, LocalDate toDate);
}

