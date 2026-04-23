package com.sidms.backend.scheduler;

import com.sidms.backend.client.MeteoGovLkClient;
import com.sidms.backend.client.MeteoGovLkClient.MeteoStationReading;
import com.sidms.backend.entity.StationObservation;
import com.sidms.backend.repository.StationObservationRepository;
import com.sidms.backend.service.SyncStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Downloads and persists Meteo.gov.lk 3-hourly station observations every 3 hours.
 *
 * Deduplication: skips rows whose (station_id, timestamp_utc) already exist in DB.
 * Source tag: "METEO_GOV_LK" stored in station_observations.source.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MeteoStationSyncScheduler {

    private static final String   JOB_NAME = "meteo_gov_lk_sync";
    private static final Duration COOLDOWN = Duration.ofHours(3);

    private static final String SOURCE = "METEO_GOV_LK";

    private final MeteoGovLkClient            meteoGovLkClient;
    private final StationObservationRepository stationObservationRepository;
    private final SyncStateService            syncStateService;

    // =========================================================================
    // Scheduled entry point
    // =========================================================================

    /**
     * Fires at 15 minutes past every 3rd hour (00:15, 03:15, 06:15 ... 21:15).
     * The 15-minute offset gives Meteo.gov.lk time to publish the Excel after the
     * nominal synoptic hour.
     */
    @Scheduled(cron = "0 15 */3 * * *")
    public void scheduledRun() {
        if (!syncStateService.shouldRun(JOB_NAME, COOLDOWN)) return;
        try {
            doSync();
            syncStateService.recordSuccess(JOB_NAME, COOLDOWN);
        } catch (Exception e) {
            log.error("[{}] Sync failed: {}", JOB_NAME, e.getMessage(), e);
            syncStateService.recordFailure(JOB_NAME, COOLDOWN, e.getMessage());
        }
    }

    // =========================================================================
    // Public sync — callable from AdminSyncController for manual override
    // =========================================================================

    public void doSync() {
        log.info("[MeteoGov] Sync started");

        List<MeteoStationReading> readings = meteoGovLkClient.fetchLatestReadings();
        int total   = readings.size();
        int inserted = 0;

        for (MeteoStationReading reading : readings) {
            try {
                // Deduplication: skip if this station + timestamp already stored
                if (stationObservationRepository.existsByStationIdAndTimestampUtc(
                        reading.getStationId(), reading.getTimestampUtc())) {
                    continue;
                }

                StationObservation obs = toEntity(reading);
                stationObservationRepository.save(obs);
                inserted++;

            } catch (Exception e) {
                log.warn("[MeteoGov] Failed to save observation for station {} at {}: {}",
                        reading.getStationId(), reading.getTimestampUtc(), e.getMessage());
            }
        }

        log.info("[MeteoGov] Inserted {}/{} observations", inserted, total);
    }

    // =========================================================================
    // Private — entity conversion
    // =========================================================================

    private StationObservation toEntity(MeteoStationReading r) {
        return StationObservation.builder()
                .stationId(r.getStationId())
                .timestampUtc(r.getTimestampUtc())
                .temperatureC(toBigDecimal(r.getTemperatureC()))
                .rainfallMm(toBigDecimal(r.getRainfallMm()))
                .humidityPct(toBigDecimal(r.getHumidityPct()))
                .weatherType(r.getWeatherType())
                .source(SOURCE)
                .rawData(r.getRawRow())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private BigDecimal toBigDecimal(Double value) {
        return value != null ? new BigDecimal(value.toString()) : null;
    }
}
