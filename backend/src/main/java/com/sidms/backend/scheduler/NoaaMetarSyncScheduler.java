package com.sidms.backend.scheduler;

import com.sidms.backend.client.NoaaMetarClient;
import com.sidms.backend.client.NoaaMetarClient.MetarReading;
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
 * Syncs NOAA METAR observations for VCBI (Katunayake) and VCRI (Mattala) every 30 minutes.
 *
 * Deduplication: skips rows whose (station_id, timestamp_utc) already exist in DB.
 * Source tag: "NOAA_METAR" stored in station_observations.source.
 * Note: METAR reports do not contain precipitation totals — rainfall_mm is always null.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NoaaMetarSyncScheduler {

    private static final String   JOB_NAME = "noaa_metar_sync";
    private static final Duration COOLDOWN = Duration.ofMinutes(30);

    private static final String SOURCE = "NOAA_METAR";

    private final NoaaMetarClient             noaaMetarClient;
    private final StationObservationRepository stationObservationRepository;
    private final SyncStateService            syncStateService;

    // =========================================================================
    // Scheduled entry point
    // =========================================================================

    /** Fires at 0 and 30 minutes past every hour. */
    @Scheduled(cron = "0 */30 * * * *")
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
        log.info("[METAR] Sync started");

        List<MetarReading> readings = noaaMetarClient.fetchLatestReadings();
        int total    = readings.size();
        int inserted = 0;

        for (MetarReading reading : readings) {
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
                log.warn("[METAR] Failed to save observation for station {} at {}: {}",
                        reading.getStationId(), reading.getTimestampUtc(), e.getMessage());
            }
        }

        log.info("[METAR] Inserted {}/{} observations", inserted, total);
    }

    // =========================================================================
    // Private — entity conversion
    // =========================================================================

    private StationObservation toEntity(MetarReading r) {
        return StationObservation.builder()
                .stationId(r.getStationId())
                .timestampUtc(r.getTimestampUtc())
                .temperatureC(toBigDecimal(r.getTemperatureC()))
                .rainfallMm(null)          // METAR does not report precipitation totals
                .humidityPct(toBigDecimal(r.getHumidityPct()))
                .windSpeedMs(toBigDecimal(r.getWindSpeedMs()))
                .weatherType(r.getWeatherType())
                .source(SOURCE)
                .rawData(buildRaw(r))
                .createdAt(LocalDateTime.now())
                .build();
    }

    /**
     * Builds a compact raw representation for audit.
     * Stores dew point and pressure which don't have dedicated columns in station_observations.
     */
    private String buildRaw(MetarReading r) {
        return "dewp=" + r.getDewPointC()
                + "|pressure_hpa=" + r.getPressureHpa()
                + "|wind_dir=" + r.getWindDirectionDeg()
                + "|source=" + SOURCE;
    }

    private BigDecimal toBigDecimal(Double value) {
        return value != null ? new BigDecimal(value.toString()) : null;
    }
}
