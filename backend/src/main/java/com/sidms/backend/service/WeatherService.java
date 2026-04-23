package com.sidms.backend.service;

import com.sidms.backend.dto.weather.SpatialUnitSearchResult;
import com.sidms.backend.dto.weather.WeatherResponse;
import com.sidms.backend.entity.GnStationAnchor;
import com.sidms.backend.entity.JaxaRainGrid;
import com.sidms.backend.entity.NodeTimeseries;
import com.sidms.backend.entity.OpenmeteoForecastEnsemble;
import com.sidms.backend.entity.SpatialForecastSnapshot;
import com.sidms.backend.entity.SpatialUnit;
import com.sidms.backend.entity.SpatialUnitWeatherNodeMapping;
import com.sidms.backend.entity.StationMetadata;
import com.sidms.backend.entity.StationObservation;
import com.sidms.backend.entity.WeatherNode;
import com.sidms.backend.entity.WeatherNodeLiveCache;
import com.sidms.backend.entity.enums.SpatialType;
import com.sidms.backend.exception.ResourceNotFoundException;
import com.sidms.backend.repository.GnStationAnchorRepository;
import com.sidms.backend.repository.JaxaRainGridRepository;
import com.sidms.backend.repository.NodeTimeseriesRepository;
import com.sidms.backend.repository.OpenmeteoForecastEnsembleRepository;
import com.sidms.backend.repository.SpatialForecastSnapshotRepository;
import com.sidms.backend.repository.SpatialUnitRepository;
import com.sidms.backend.repository.SpatialUnitWeatherNodeMappingRepository;
import com.sidms.backend.repository.StationMetadataRepository;
import com.sidms.backend.repository.StationObservationRepository;
import com.sidms.backend.repository.WeatherNodeLiveCacheRepository;
import com.sidms.backend.repository.WeatherNodeRepository;
import com.sidms.backend.util.CacheKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WeatherService {

    private final SpatialUnitRepository                   spatialUnitRepository;
    private final SpatialUnitWeatherNodeMappingRepository mappingRepository;
    private final WeatherNodeLiveCacheRepository          liveCacheRepository;
    private final SpatialForecastSnapshotRepository       spatialForecastSnapshotRepository;
    private final GnStationAnchorRepository               gnStationAnchorRepository;
    private final StationObservationRepository            stationObservationRepository;
    private final StationMetadataRepository               stationMetadataRepository;
    private final WeatherNodeRepository                   weatherNodeRepository;
    private final NodeTimeseriesRepository                nodeTimeseriesRepository;
    private final JaxaRainGridRepository                  jaxaRainGridRepository;
    private final OpenmeteoForecastEnsembleRepository     openmeteoForecastEnsembleRepository;
    private final com.sidms.backend.repository.WeatherNodeCelestialRepository weatherNodeCelestialRepository;
    private final StringRedisTemplate                     redisTemplate;
    private final ObjectMapper                            objectMapper;

    public WeatherService(SpatialUnitRepository spatialUnitRepository,
                          SpatialUnitWeatherNodeMappingRepository mappingRepository,
                          WeatherNodeLiveCacheRepository liveCacheRepository,
                          SpatialForecastSnapshotRepository spatialForecastSnapshotRepository,
                          GnStationAnchorRepository gnStationAnchorRepository,
                          StationObservationRepository stationObservationRepository,
                          StationMetadataRepository stationMetadataRepository,
                          WeatherNodeRepository weatherNodeRepository,
                          NodeTimeseriesRepository nodeTimeseriesRepository,
                          JaxaRainGridRepository jaxaRainGridRepository,
                          OpenmeteoForecastEnsembleRepository openmeteoForecastEnsembleRepository,
                          com.sidms.backend.repository.WeatherNodeCelestialRepository weatherNodeCelestialRepository,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper) {
        this.spatialUnitRepository             = spatialUnitRepository;
        this.mappingRepository                 = mappingRepository;
        this.liveCacheRepository               = liveCacheRepository;
        this.spatialForecastSnapshotRepository = spatialForecastSnapshotRepository;
        this.gnStationAnchorRepository         = gnStationAnchorRepository;
        this.stationObservationRepository      = stationObservationRepository;
        this.stationMetadataRepository         = stationMetadataRepository;
        this.weatherNodeRepository             = weatherNodeRepository;
        this.nodeTimeseriesRepository          = nodeTimeseriesRepository;
        this.jaxaRainGridRepository            = jaxaRainGridRepository;
        this.openmeteoForecastEnsembleRepository = openmeteoForecastEnsembleRepository;
        this.weatherNodeCelestialRepository    = weatherNodeCelestialRepository;
        this.redisTemplate                     = redisTemplate;
        this.objectMapper                      = objectMapper;
    }

    public WeatherResponse getWeatherForSpatialUnit(UUID spatialUnitId) {
        String cacheKey = "weather:spatial:" + spatialUnitId;

        // ── 0. Load spatial unit first (needed for direct-serve and lapse rate) ──
        SpatialUnit spatialUnit = spatialUnitRepository.findById(spatialUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("Spatial unit not found: " + spatialUnitId));

        // ── CHANGE 2: Station direct serving ─────────────────────────────────────
        // GN divisions within 2 km of a real station receive its reading directly
        // if the observation is ≤ 3 hours old (bypasses IDW entirely).
        Optional<GnStationAnchor> anchor = gnStationAnchorRepository.findFirstByIdGnId(spatialUnitId);
        if (anchor.isPresent()) {
            Optional<StationObservation> stationObs = stationObservationRepository
                    .findTopByStationIdOrderByTimestampUtcDesc(anchor.get().getId().getStationId());
            if (stationObs.isPresent()) {
                StationObservation obs = stationObs.get();
                long ageMinutes = Duration.between(obs.getTimestampUtc(), LocalDateTime.now(ZoneOffset.UTC)).toMinutes();
                if (ageMinutes <= 180) {
                    WeatherResponse directResponse = WeatherResponse.builder()
                            .spatialUnitId(spatialUnit.getId().toString())
                            .spatialUnitName(spatialUnit.getName())
                            .spatialUnitType(spatialUnit.getType().name())
                            .tempC(toDouble(obs.getTemperatureC()))
                            .humidityPct(toDouble(obs.getHumidityPct()))
                            .windSpeedKmh(obs.getWindSpeedMs() != null
                                    ? obs.getWindSpeedMs().doubleValue() * 3.6 : null)
                            .precipitationMm(toDouble(obs.getRainfallMm()))
                            .fetchedAt(obs.getTimestampUtc())
                            .dataQuality("STATION_DIRECT")
                            .build();
                    cacheWeatherResponse(cacheKey, directResponse);
                    log.info("[WeatherService] Station-direct response for {} from station {}",
                            spatialUnitId, anchor.get().getId().getStationId());
                    return directResponse;
                }
            }
        }

        // ── 1. Check Redis cache ──────────────────────────────────────────────────
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, WeatherResponse.class);
            }
        } catch (Exception ignored) {
            // Redis down or parse error — continue with DB
        }

        // ── CHANGE 3: Load IDW mappings — limited to top 6 by rank ───────────────
        List<SpatialUnitWeatherNodeMapping> mappings = mappingRepository
                .findBySpatialUnitIdOrderByRankAsc(spatialUnitId)
                .stream().limit(4).collect(Collectors.toList());

        if (mappings.isEmpty()) {
            throw new ResourceNotFoundException("No IDW mappings found. Run setup first.");
        }

        // ── CHANGE 3: Build data map from NodeTimeseries (fallback → LiveCache) ───
        List<UUID> nodeIds = mappings.stream()
                .map(SpatialUnitWeatherNodeMapping::getWeatherNodeId)
                .collect(Collectors.toList());

        // Pre-fetch live cache as fallback
        Map<UUID, WeatherNodeLiveCache> liveMap = liveCacheRepository.findAllById(nodeIds).stream()
                .collect(Collectors.toMap(WeatherNodeLiveCache::getWeatherNodeId, c -> c));

        // Pre-fetch node entities for bias and elevation
        Map<UUID, WeatherNode> nodeMap = weatherNodeRepository.findAllById(nodeIds).stream()
                .collect(Collectors.toMap(WeatherNode::getId, n -> n));

        // ── 5. Compute IDW weighted averages ─────────────────────────────────────
        double totalWeight = 0;
        double wTempC = 0, wApparentTempC = 0, wHumidity = 0, wPressure = 0;
        double wPrecip = 0, wPrecipProb = 0, wWindSpeed = 0, wWindGust = 0;
        double wWindDir = 0, wCloudCover = 0, wUvIndex = 0, wCape = 0;
        double wAqi = 0, wPm10 = 0, wPm25 = 0, wVisibility = 0;
        int nodeCount = 0;
        Integer dominantWeatherCode = null;
        String dominantSymbolCode = null;
        Integer dominantIsDay = null;
        LocalDateTime latestFetch = null;
        boolean hasNonZeroBias = false;

        // Per-field weight sums for fields that may be null
        double swTempC = 0, swApparentTempC = 0, swHumidity = 0, swPressure = 0;
        double swPrecip = 0, swPrecipProb = 0, swWindSpeed = 0, swWindGust = 0;
        double swWindDir = 0, swCloudCover = 0, swUvIndex = 0, swCape = 0;
        double swAqi = 0, swPm10 = 0, swPm25 = 0, swVisibility = 0;

        for (SpatialUnitWeatherNodeMapping mapping : mappings) {
            UUID nodeId = mapping.getWeatherNodeId();
            double w = mapping.getIdwWeight();

            // ── CHANGE 3: Resolve temperature from NodeTimeseries first ──────────
            Double rawTempC = null;
            Double humidity = null, pressure = null, precip = null, windSpeed = null;
            Double windDir = null;
            Double apparentTemp = null, precipProb = null, windGust = null;
            Double cloudCover = null, uvIndex = null, cape = null, aqi = null;
            Double pm10 = null, pm25 = null, visibility = null;
            Integer weatherCode = null;
            String symbolCode = null;
            LocalDateTime fetchedAt = null;
            String sourceApi = null;
            String rawPayload = null;

            Optional<NodeTimeseries> tsOpt =
                    nodeTimeseriesRepository.findTopByNodeIdAndForecastHourOrderByValidFromUtcDesc(nodeId, 0);

            if (tsOpt.isPresent()) {
                NodeTimeseries ts = tsOpt.get();
                rawTempC   = toDouble(ts.getTemperatureC());
                humidity   = toDouble(ts.getHumidityPct());
                pressure   = toDouble(ts.getPressureHpa());
                precip     = toDouble(ts.getPrecipitationMm());
                windSpeed  = ts.getWindSpeedMs() != null
                        ? ts.getWindSpeedMs().doubleValue() * 3.6 : null; // m/s → km/h
                windDir    = ts.getWindDirectionDeg() != null
                        ? ts.getWindDirectionDeg().doubleValue() : null;
                weatherCode = ts.getWeatherCode();
                symbolCode  = ts.getSymbolCode();
                fetchedAt  = ts.getValidFromUtc();
                sourceApi  = "yr-no";
            } else {
                // Fallback to live cache (older Open-Meteo data)
                WeatherNodeLiveCache cache = liveMap.get(nodeId);
                if (cache != null) {
                    rawTempC    = cache.getTempC();
                    humidity    = cache.getHumidityPct();
                    pressure    = cache.getPressureHpa();
                    precip      = cache.getPrecipitationMm();
                    precipProb  = cache.getPrecipProbability();
                    windSpeed   = cache.getWindSpeedKmh();
                    windGust    = cache.getWindGustKmh();
                    windDir     = cache.getWindDirectionDeg();
                    cloudCover  = cache.getCloudCoverPct();
                    uvIndex     = cache.getUvIndex();
                    cape        = cache.getCapeJkg();
                    aqi         = cache.getUsAqi();
                    pm10        = cache.getPm10();
                    pm25        = cache.getPm25();
                    visibility  = cache.getVisibilityM();
                    apparentTemp= cache.getApparentTempC();
                    weatherCode = cache.getWeatherCode();
                    symbolCode  = cache.getSymbolCode();
                    fetchedAt   = cache.getFetchedAt();
                    sourceApi   = cache.getSourceApi();
                    rawPayload  = cache.getRawPayload();
                }
            }

            if (rawTempC == null && humidity == null) continue; // no usable data
            nodeCount++;

            // ── CHANGE 4: Apply bias correction to temperature ──────────────────
            double correctedTemp = rawTempC != null ? rawTempC : 0.0;
            boolean appliedBias = false;
            if (rawTempC != null) {
                WeatherNode node = nodeMap.get(nodeId);
                double bias = (node != null && node.getBiasTempC() != null)
                        ? node.getBiasTempC() : 0.0;
                correctedTemp = rawTempC + bias;
                if (bias != 0.0) appliedBias = true;
            }

            // Accumulate weighted sums
            if (rawTempC != null)    { wTempC += correctedTemp * w; swTempC += w; if (appliedBias) hasNonZeroBias = true; }
            if (apparentTemp != null){ wApparentTempC += apparentTemp * w; swApparentTempC += w; }
            if (humidity != null)    { wHumidity += humidity * w; swHumidity += w; }
            if (pressure != null)    { wPressure += pressure * w; swPressure += w; }
            if (precip != null)      { wPrecip += precip * w; swPrecip += w; }
            if (precipProb != null)  { wPrecipProb += precipProb * w; swPrecipProb += w; }
            if (windSpeed != null)   { wWindSpeed += windSpeed * w; swWindSpeed += w; }
            if (windGust != null)    { wWindGust += windGust * w; swWindGust += w; }
            if (windDir != null)     { wWindDir += windDir * w; swWindDir += w; }
            if (cloudCover != null)  { wCloudCover += cloudCover * w; swCloudCover += w; }
            if (uvIndex != null)     { wUvIndex += uvIndex * w; swUvIndex += w; }
            if (cape != null)        { wCape += cape * w; swCape += w; }
            if (aqi != null)         { wAqi += aqi * w; swAqi += w; }
            if (pm10 != null)        { wPm10 += pm10 * w; swPm10 += w; }
            if (pm25 != null)        { wPm25 += pm25 * w; swPm25 += w; }
            if (visibility != null)  { wVisibility += visibility * w; swVisibility += w; }

            // Weather code, symbol code and isDay from primary (rank 1) node
            if (mapping.getRank() == 1) {
                if (weatherCode != null) dominantWeatherCode = weatherCode;
                if (symbolCode != null) dominantSymbolCode = symbolCode;
            }
            if (fetchedAt != null) {
                if (latestFetch == null || fetchedAt.isAfter(latestFetch)) {
                    latestFetch = fetchedAt;
                }
            }
            totalWeight += w;
        }

        // ── Data quality ─────────────────────────────────────────────────────────
        String dataQuality;
        if (nodeCount == mappings.size()) {
            dataQuality = "LIVE";
        } else if (nodeCount > 0) {
            dataQuality = "ESTIMATED";
        } else {
            dataQuality = "CACHED";
        }

        // If we have bias-corrected model data, upgrade the label
        // (station anchor existed but was stale — we fell through to IDW)
        if (hasNonZeroBias && anchor.isPresent()) {
            dataQuality = "MODEL_BIAS_CORRECTED";
        }

        // ── CHANGE 5: Elevation lapse rate correction ─────────────────────────────
        // Environmental lapse rate: -6.5 °C per 1000 m ascent → -0.0065 °C/m
        Double weightedTempC = safeDivide(wTempC, swTempC);
        Double finalTemp = weightedTempC;

        if (weightedTempC != null) {
            double avgNodeElevation = mappings.stream()
                    .map(m -> nodeMap.get(m.getWeatherNodeId()))
                    .filter(Objects::nonNull)
                    .mapToDouble(n -> n.getElevationM() != null ? n.getElevationM().doubleValue() : 0.0)
                    .average()
                    .orElse(0.0);

            double unitElevation = (spatialUnit.getElevationM() != null)
                    ? spatialUnit.getElevationM() : 0.0;
            double lapseCorrection = (unitElevation - avgNodeElevation) * -0.0065;
            finalTemp = Math.round((weightedTempC + lapseCorrection) * 100.0) / 100.0;
        }

        // ── Dew point via Magnus formula ──────────────────────────────────────────
        Double dewPoint = null;
        Double finalHumidity = safeDivide(wHumidity, swHumidity);
        if (finalTemp != null && finalHumidity != null && finalHumidity > 0) {
            double alpha = ((17.625 * finalTemp) / (243.04 + finalTemp))
                    + Math.log(finalHumidity / 100.0);
            dewPoint = Math.round((243.04 * alpha) / (17.625 - alpha) * 100.0) / 100.0;
        }

        // ── Fetch sunrise/sunset/is_day from Celestial DB for rank-1 node ─────────
        String sunrise = null;
        String sunset  = null;
        try {
            if (!mappings.isEmpty()) {
                UUID rank1NodeId = mappings.get(0).getWeatherNodeId();
                Optional<com.sidms.backend.entity.WeatherNodeCelestial> celestialOpt = weatherNodeCelestialRepository
                        .findByWeatherNodeIdAndRecordDate(rank1NodeId, LocalDate.now(ZoneOffset.UTC));
                if (celestialOpt.isPresent()) {
                    com.sidms.backend.entity.WeatherNodeCelestial celestial = celestialOpt.get();
                    if (celestial.getSunriseTime() != null) {
                        sunrise = celestial.getSunriseTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    }
                    if (celestial.getSunsetTime() != null) {
                        sunset = celestial.getSunsetTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    }
                    
                    if (celestial.getSunriseTime() != null && celestial.getSunsetTime() != null) {
                        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(ZoneOffset.UTC);
                        dominantIsDay = (now.isAfter(celestial.getSunriseTime()) && now.isBefore(celestial.getSunsetTime())) ? 1 : 0;
                    }
                }
            }
        } catch (Exception e) {
            // Non-critical — continue without solar data
            log.warn("[WeatherService] Failed to load celestial data: {}", e.getMessage());
        }

        // ── Rainfall fusion (station → JAXA satellite → IDW model) ───────────────
        Double fusedRainfall = getRainfallMm(
                spatialUnitId,
                spatialUnit.getLat() != null ? spatialUnit.getLat() : 0.0,
                spatialUnit.getLng() != null ? spatialUnit.getLng() : 0.0,
                mappings,
                nodeTimeseriesRepository,
                liveCacheRepository);
        // fusedRainfall overrides the IDW precipitation only when a higher-priority source is available
        Double finalPrecipMm = fusedRainfall != null ? fusedRainfall : safeDivide(wPrecip, swPrecip);

        // ── Build response ────────────────────────────────────────────────────────
        WeatherResponse response = WeatherResponse.builder()
                .spatialUnitId(spatialUnit.getId().toString())
                .spatialUnitName(spatialUnit.getName())
                .spatialUnitType(spatialUnit.getType().name())
                .tempC(finalTemp)
                .apparentTempC(safeDivide(wApparentTempC, swApparentTempC))
                .dewPointC(dewPoint)
                .humidityPct(finalHumidity)
                .pressureHpa(safeDivide(wPressure, swPressure))
                .visibilityM(safeDivide(wVisibility, swVisibility))
                .precipitationMm(finalPrecipMm)
                .precipProbability(safeDivide(wPrecipProb, swPrecipProb))
                .windSpeedKmh(safeDivide(wWindSpeed, swWindSpeed))
                .windGustKmh(safeDivide(wWindGust, swWindGust))
                .windDirectionDeg(safeDivide(wWindDir, swWindDir))
                .cloudCoverPct(safeDivide(wCloudCover, swCloudCover))
                .uvIndex(safeDivide(wUvIndex, swUvIndex))
                .capeJkg(safeDivide(wCape, swCape))
                .weatherCode(dominantWeatherCode)
                .symbolCode(dominantSymbolCode)
                .isDay(dominantIsDay)
                .usAqi(safeDivide(wAqi, swAqi))
                .pm10(safeDivide(wPm10, swPm10))
                .pm25(safeDivide(wPm25, swPm25))
                .sunrise(sunrise)
                .sunset(sunset)
                .fetchedAt(latestFetch)
                .dataQuality(dataQuality)
                .build();

        cacheWeatherResponse(cacheKey, response);
        return response;
    }

    public List<SpatialUnitSearchResult> searchSpatialUnits(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        String q = query.trim();

        // Check Redis cache
        String cacheKey = "search:locations:" + q.toLowerCase();
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructCollectionType(List.class,
                                SpatialUnitSearchResult.class));
            }
        } catch (Exception ignored) {
        }

        // Query DB
        List<SpatialUnit> units = spatialUnitRepository
                .findByNameContainingIgnoreCaseOrNameSinhalaContainingIgnoreCaseOrNameTamilContainingIgnoreCase(
                        q, q, q);

        List<SpatialUnitSearchResult> results = units.stream()
                .limit(20)
                .map(su -> {
                    String parentName = null;
                    if (su.getParentId() != null) {
                        parentName = spatialUnitRepository.findById(su.getParentId())
                                .map(SpatialUnit::getName)
                                .orElse(null);
                    }
                    return SpatialUnitSearchResult.builder()
                            .id(su.getId())
                            .pcode(su.getPcode())
                            .name(su.getName())
                            .type(su.getType().name())
                            .parentName(parentName)
                            .lat(su.getLat())
                            .lng(su.getLng())
                            .build();
                })
                .collect(Collectors.toList());

        // Cache for 60 minutes if not empty
        if (!results.isEmpty()) {
            try {
                redisTemplate.opsForValue().set(cacheKey,
                        objectMapper.writeValueAsString(results),
                        Duration.ofMinutes(60));
            } catch (Exception ignored) {
            }
        }

        return results;
    }

    public WeatherResponse getNearestSpatialUnit(Double lat, Double lng) {
        // Find nearest GN_DIVISION
        List<SpatialUnit> gnDivisions = spatialUnitRepository.findByType(SpatialType.GN_DIVISION);

        SpatialUnit nearest = gnDivisions.stream()
                .filter(su -> su.getLat() != null && su.getLng() != null)
                .min(Comparator.comparingDouble(
                        su -> Math.sqrt(Math.pow(su.getLat() - lat, 2) + Math.pow(su.getLng() - lng, 2))))
                .orElseThrow(() -> new ResourceNotFoundException("No GN divisions found in the system"));

        return getWeatherForSpatialUnit(nearest.getId());
    }

    public List<WeatherResponse> getAllTrackedWeather() {
        List<UUID> spatialUnitIds = mappingRepository.findAll().stream()
                .map(SpatialUnitWeatherNodeMapping::getSpatialUnitId)
                .distinct()
                .collect(Collectors.toList());

        return spatialUnitIds.stream()
                .map(id -> {
                    try {
                        return getWeatherForSpatialUnit(id);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Map<String, Object> getForecastWeather(Double lat, Double lng) {
        // Find nearest city for the context
        List<SpatialUnit> gnDivisions = spatialUnitRepository.findByType(SpatialType.GN_DIVISION);
        SpatialUnit nearest = gnDivisions.stream()
                .filter(su -> su.getLat() != null && su.getLng() != null)
                .min(Comparator.comparingDouble(
                        su -> Math.sqrt(Math.pow(su.getLat() - lat, 2) + Math.pow(su.getLng() - lng, 2))))
                .orElse(null);

        Map<String, Object> result = new HashMap<>();
        if (nearest != null) {
            result.put("spatialUnitId",   nearest.getId().toString());
            result.put("spatialUnitName", nearest.getName());
            result.put("lat",             nearest.getLat());
            result.put("lng",             nearest.getLng());
        } else {
            result.put("lat", lat);
            result.put("lng", lng);
        }

        JsonNode data = null;
        if (nearest != null) {
            String forecastCacheKey = CacheKeys.weatherForecastSpatial(nearest.getId().toString());
            try {
                String cachedForecast = redisTemplate.opsForValue().get(forecastCacheKey);
                if (cachedForecast != null && !cachedForecast.isBlank()) {
                    data = objectMapper.readTree(cachedForecast);
                }
            } catch (Exception ignored) {
            }

            List<SpatialUnitWeatherNodeMapping> fMappings = mappingRepository
                    .findBySpatialUnitIdOrderByRankAsc(nearest.getId());
            if (data == null && !fMappings.isEmpty()) {
                UUID rank1NodeId = fMappings.get(0).getWeatherNodeId();
                WeatherNodeLiveCache cache = liveCacheRepository.findById(rank1NodeId).orElse(null);
                if (cache != null && cache.getRawPayload() != null
                        && "open-meteo".equals(cache.getSourceApi())) {
                    try {
                        data = objectMapper.readTree(cache.getRawPayload());
                    } catch (Exception ignored) {
                    }
                }
            }

            if (data == null) {
                try {
                    SpatialForecastSnapshot snapshot = spatialForecastSnapshotRepository
                            .findBySpatialUnitId(nearest.getId())
                            .orElse(null);
                    if (snapshot != null && snapshot.getPayload() != null
                            && !snapshot.getPayload().isBlank()) {
                        data = objectMapper.readTree(snapshot.getPayload());
                        redisTemplate.opsForValue().set(
                                forecastCacheKey,
                                snapshot.getPayload(),
                                CacheKeys.TTL_FORECAST_SHORT);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (data != null) {
            result.put("daily",   data.get("daily"));
        } else {
            result.put("daily",   null);
        }

        buildYrNoTimeseriesData(nearest, result);

        // ── Extended forecast: days 8-14 from ensemble percentiles ────────────────
        result.put("extended", buildExtendedForecast(nearest));

        return result;
    }

    private void buildYrNoTimeseriesData(SpatialUnit nearest, Map<String, Object> result) {
        if (nearest == null) {
            result.put("current", null);
            result.put("hourly", null);
            return;
        }

        List<SpatialUnitWeatherNodeMapping> fMappings = mappingRepository
                .findBySpatialUnitIdOrderByRankAsc(nearest.getId());
        if (fMappings.isEmpty()) {
            result.put("current", null);
            result.put("hourly", null);
            return;
        }

        UUID primaryNodeId = fMappings.get(0).getWeatherNodeId();
        List<NodeTimeseries> timeseries = nodeTimeseriesRepository.findByNodeIdOrderByForecastHourAsc(primaryNodeId);

        if (timeseries.isEmpty()) {
            result.put("current", null);
            result.put("hourly", null);
            return;
        }

        Map<String, Object> hourly = new LinkedHashMap<>();
        List<String> time = new ArrayList<>();
        List<Double> temp = new ArrayList<>();
        List<Double> humidity = new ArrayList<>();
        List<Double> precip = new ArrayList<>();
        List<Integer> weatherCode = new ArrayList<>();

        for (NodeTimeseries ts : timeseries) {
            time.add(ts.getValidFromUtc().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            temp.add(toDouble(ts.getTemperatureC()));
            humidity.add(toDouble(ts.getHumidityPct()));
            precip.add(toDouble(ts.getPrecipitationMm()));
            weatherCode.add(ts.getWeatherCode());
        }

        hourly.put("time", time);
        hourly.put("temperature_2m", temp);
        hourly.put("relative_humidity_2m", humidity);
        hourly.put("precipitation", precip);
        hourly.put("weather_code", weatherCode);
        result.put("hourly", hourly);

        Map<String, Object> current = new LinkedHashMap<>();
        NodeTimeseries ts0 = timeseries.get(0);
        current.put("time", ts0.getValidFromUtc().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        current.put("temperature_2m", toDouble(ts0.getTemperatureC()));
        current.put("relative_humidity_2m", toDouble(ts0.getHumidityPct()));
        current.put("precipitation", toDouble(ts0.getPrecipitationMm()));
        current.put("weather_code", ts0.getWeatherCode());
        
        // Calculate is_day
        int isDay = 1;
        try {
            Optional<com.sidms.backend.entity.WeatherNodeCelestial> celestialOpt = weatherNodeCelestialRepository
                    .findByWeatherNodeIdAndRecordDate(primaryNodeId, LocalDate.now());
            if (celestialOpt.isPresent()) {
                com.sidms.backend.entity.WeatherNodeCelestial celestial = celestialOpt.get();
                if (celestial.getSunriseTime() != null && celestial.getSunsetTime() != null) {
                    java.time.OffsetDateTime now = java.time.OffsetDateTime.now(ZoneOffset.UTC);
                    isDay = (now.isAfter(celestial.getSunriseTime()) && now.isBefore(celestial.getSunsetTime())) ? 1 : 0;
                }
            }
        } catch (Exception ignored) {
        }
        current.put("is_day", isDay);
        
        result.put("current", current);
    }

    /**
     * Queries {@code openmeteo_forecast_ensemble} for the primary node of the given
     * spatial unit and returns days [today+8 .. today+14] as a list of maps.
     *
     * Each entry has:
     *   date         — ISO date string
     *   tempMin      — {p10, p50, p90}
     *   tempMax      — {p10, p50, p90}
     *   rainProbability — fraction (0-1) or null
     *   confidence   — "LOW" (ensemble spread is inherently uncertain at this range)
     *
     * Returns an empty list if no data is available or nearest is null.
     */
    private List<Map<String, Object>> buildExtendedForecast(SpatialUnit nearest) {
        if (nearest == null) return List.of();

        try {
            List<SpatialUnitWeatherNodeMapping> fMappings = mappingRepository
                    .findBySpatialUnitIdOrderByRankAsc(nearest.getId());
            if (fMappings.isEmpty()) return List.of();

            UUID primaryNodeId = fMappings.get(0).getWeatherNodeId();

            LocalDate fromDate = LocalDate.now().plusDays(8);
            LocalDate toDate   = LocalDate.now().plusDays(14);

            List<OpenmeteoForecastEnsemble> rows =
                    openmeteoForecastEnsembleRepository
                            .findByNodeIdAndForecastDateBetweenOrderByForecastDateAsc(
                                    primaryNodeId, fromDate, toDate);

            List<Map<String, Object>> extended = new ArrayList<>(rows.size());
            for (OpenmeteoForecastEnsemble row : rows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("date", row.getForecastDate().toString());

                Map<String, Object> tempMin = new LinkedHashMap<>();
                tempMin.put("p10", row.getTempMinP10() != null ? row.getTempMinP10().doubleValue() : null);
                tempMin.put("p50", row.getTempMinP50() != null ? row.getTempMinP50().doubleValue() : null);
                tempMin.put("p90", row.getTempMinP90() != null ? row.getTempMinP90().doubleValue() : null);

                Map<String, Object> tempMax = new LinkedHashMap<>();
                tempMax.put("p10", row.getTempMaxP10() != null ? row.getTempMaxP10().doubleValue() : null);
                tempMax.put("p50", row.getTempMaxP50() != null ? row.getTempMaxP50().doubleValue() : null);
                tempMax.put("p90", row.getTempMaxP90() != null ? row.getTempMaxP90().doubleValue() : null);

                entry.put("tempMin", tempMin);
                entry.put("tempMax", tempMax);
                entry.put("rainProbability", row.getPrecipitationProbability() != null
                        ? row.getPrecipitationProbability().doubleValue() : null);
                entry.put("confidence", "LOW");

                extended.add(entry);
            }
            return extended;

        } catch (Exception e) {
            log.warn("[WeatherService] Failed to build extended forecast for {}: {}", nearest.getId(), e.getMessage());
            return List.of();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Stores a WeatherResponse in Redis for 10 minutes. Swallows failures silently. */
    private void cacheWeatherResponse(String cacheKey, WeatherResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(response),
                    Duration.ofMinutes(10));
        } catch (Exception ignored) {
            // Redis down — still return response
        }
    }

    private Double safeDivide(double numerator, double denominator) {
        if (denominator == 0) return null;
        return Math.round(numerator / denominator * 100.0) / 100.0;
    }

    /** Null-safe BigDecimal → Double conversion. */
    private Double toDouble(BigDecimal bd) {
        return bd != null ? bd.doubleValue() : null;
    }

    // ── Rainfall fusion ───────────────────────────────────────────────────────

    /**
     * Priority fusion pipeline for precipitation:
     *   1. Physical station within 10 km with a fresh observation (≤ 3h)
     *   2. JAXA GSMaP satellite grid cell within ±0.06° and ≤ 2h old
     *   3. IDW-weighted model precipitation from node_timeseries / live cache
     *
     * Returns null only if all three priorities produce no data.
     *
     * @param mappings              the top-6 IDW mappings already loaded in the caller
     * @param nodeTimeseriesRepo    injected repo (passed to avoid re-fetching)
     * @param liveCacheRepo         injected repo (passed to avoid re-fetching)
     */
    private Double getRainfallMm(
            UUID spatialUnitId,
            double lat,
            double lng,
            List<SpatialUnitWeatherNodeMapping> mappings,
            NodeTimeseriesRepository nodeTimeseriesRepo,
            WeatherNodeLiveCacheRepository liveCacheRepo) {

        // ── Priority 1: Physical station within 10 km ─────────────────────────
        try {
            List<StationMetadata> allStations = stationMetadataRepository.findAll();
            LocalDateTime stationCutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(3);

            StationMetadata nearest10km = null;
            double minDist = Double.MAX_VALUE;

            for (StationMetadata station : allStations) {
                if (station.getLatitude() == null || station.getLongitude() == null) continue;
                double dist = haversineKm(
                        station.getLatitude().doubleValue(),
                        station.getLongitude().doubleValue(),
                        lat, lng);
                if (dist < 10.0 && dist < minDist) {
                    minDist = dist;
                    nearest10km = station;
                }
            }

            if (nearest10km != null) {
                Optional<StationObservation> obsOpt = stationObservationRepository
                        .findTopByStationIdAndTimestampUtcAfterOrderByTimestampUtcDesc(
                                nearest10km.getStationId(), stationCutoff);
                if (obsOpt.isPresent() && obsOpt.get().getRainfallMm() != null) {
                    log.debug("[WeatherService] Rainfall P1 (station {}, {:.1f}km) for {}",
                            nearest10km.getStationId(), minDist, spatialUnitId);
                    return toDouble(obsOpt.get().getRainfallMm());
                }
            }
        } catch (Exception e) {
            log.warn("[WeatherService] Rainfall P1 (station) failed: {}", e.getMessage());
        }

        // ── Priority 2: JAXA GSMaP satellite ─────────────────────────────────
        try {
            // Snap lat/lon to nearest 0.1° grid centre (offset +0.05°)
            double gridLat = Math.round(lat  * 10.0) / 10.0 + 0.05;
            double gridLon = Math.round(lng  * 10.0) / 10.0 + 0.05;

            Optional<JaxaRainGrid> gridOpt = jaxaRainGridRepository
                    .findTopByGridLatBetweenAndGridLonBetweenAndTimestampUtcAfterOrderByTimestampUtcDesc(
                            gridLat - 0.06, gridLat + 0.06,
                            gridLon - 0.06, gridLon + 0.06,
                            LocalDateTime.now(ZoneOffset.UTC).minusHours(6));
            if (gridOpt.isPresent()) {
                log.debug("[WeatherService] Rainfall P2 (JAXA) for {}", spatialUnitId);
                BigDecimal jaxa = gridOpt.get().getRainfallMm();
                return jaxa != null ? jaxa.doubleValue() : null;
            }
        } catch (Exception e) {
            log.warn("[WeatherService] Rainfall P2 (JAXA) failed: {}", e.getMessage());
        }

        // ── Priority 3: IDW model fallback ────────────────────────────────────
        try {
            double wPrecip = 0.0, swPrecip = 0.0;

            for (SpatialUnitWeatherNodeMapping mapping : mappings) {
                UUID nodeId = mapping.getWeatherNodeId();
                double w = mapping.getIdwWeight();
                Double precip = null;

                // NodeTimeseries preferred
                Optional<NodeTimeseries> tsOpt = nodeTimeseriesRepo
                        .findTopByNodeIdAndForecastHourOrderByValidFromUtcDesc(nodeId, 0);
                if (tsOpt.isPresent() && tsOpt.get().getPrecipitationMm() != null) {
                    precip = toDouble(tsOpt.get().getPrecipitationMm());
                } else {
                    // Fallback to live cache
                    WeatherNodeLiveCache cache = liveCacheRepo.findById(nodeId).orElse(null);
                    if (cache != null) precip = cache.getPrecipitationMm();
                }

                if (precip != null) {
                    wPrecip  += precip * w;
                    swPrecip += w;
                }
            }

            if (swPrecip > 0) {
                log.debug("[WeatherService] Rainfall P3 (IDW model) for {}", spatialUnitId);
                return safeDivide(wPrecip, swPrecip);
            }
        } catch (Exception e) {
            log.warn("[WeatherService] Rainfall P3 (IDW model) failed: {}", e.getMessage());
        }

        return null; // no data available from any source
    }

    /**
     * Great-circle distance between two lat/lon points in kilometres.
     * Earth radius = 6371 km.
     */
    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
