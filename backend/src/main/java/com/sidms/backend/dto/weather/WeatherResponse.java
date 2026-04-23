package com.sidms.backend.dto.weather;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherResponse {
    private String spatialUnitId;
    private String spatialUnitName;
    private String spatialUnitType;
    // Core temperature
    private Double tempC;
    private Double apparentTempC;
    private Double dewPointC;
    // Atmospheric
    private Double humidityPct;
    private Double pressureHpa;
    private Double visibilityM;
    // Precipitation
    private Double precipitationMm;
    private Double precipProbability;
    // Wind
    private Double windSpeedKmh;
    private Double windGustKmh;
    private Double windDirectionDeg;
    // Sky
    private Double cloudCoverPct;
    private Double uvIndex;
    // Convection / storm risk
    private Double capeJkg;
    // Condition
    private Integer weatherCode;
    private String symbolCode;
    private Integer isDay;
    // Air quality
    private Double usAqi;
    private Double pm10;
    private Double pm25;
    // Solar cycle (from forecast daily)
    private String sunrise;
    private String sunset;
    // Meta
    private LocalDateTime fetchedAt;
    private String dataQuality;
}
