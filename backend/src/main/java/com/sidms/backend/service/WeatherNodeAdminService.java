package com.sidms.backend.service;

import com.sidms.backend.dto.admin.WeatherNodeDto;
import com.sidms.backend.dto.admin.WeatherNodeTelemetrySummaryDto;
import com.sidms.backend.entity.WeatherNode;
import com.sidms.backend.entity.WeatherNodeLiveCache;
import com.sidms.backend.repository.WeatherNodeRepository;
import com.sidms.backend.repository.WeatherNodeLiveCacheRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Transactional
public class WeatherNodeAdminService {

    private final WeatherNodeRepository weatherNodeRepository;
    private final WeatherNodeLiveCacheRepository weatherNodeLiveCacheRepository;
    private final ObjectMapper objectMapper;

    public WeatherNodeAdminService(WeatherNodeRepository weatherNodeRepository,
            WeatherNodeLiveCacheRepository weatherNodeLiveCacheRepository,
            ObjectMapper objectMapper) {
        this.weatherNodeRepository = weatherNodeRepository;
        this.weatherNodeLiveCacheRepository = weatherNodeLiveCacheRepository;
        this.objectMapper = objectMapper;
    }

    public Page<WeatherNodeDto> getAllWeatherNodes(Pageable pageable, String search, Boolean isActive,
            Boolean isVolatile, Boolean isCoastal, Boolean isMountain) {
        Specification<WeatherNode> spec = Specification.where(null);

        if (search != null && !search.isBlank()) {
            String normalized = "%" + search.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), normalized),
                    cb.like(cb.lower(root.get("gridKey")), normalized)));
        }
        if (isActive != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("isActive"), isActive));
        }
        if (isVolatile != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("isVolatile"), isVolatile));
        }
        if (isCoastal != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("isCoastal"), isCoastal));
        }
        if (isMountain != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("isMountain"), isMountain));
        }

        return weatherNodeRepository.findAll(spec, pageable).map(this::toDto);
    }

    public WeatherNodeDto getWeatherNodeById(UUID id) {
        WeatherNode entity = weatherNodeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Weather Node not found"));
        return toDto(entity);
    }

    public WeatherNodeDto createWeatherNode(WeatherNodeDto dto) {
        WeatherNode entity = WeatherNode.builder()
                .code(dto.getCode())
                .gridKey(dto.getGridKey())
                .lat(dto.getLat())
                .lng(dto.getLng())
                .elevationM(dto.getElevationM())
                .zoneDensity(dto.getZoneDensity())
                .isCoastal(dto.getIsCoastal() != null ? dto.getIsCoastal() : false)
                .isMountain(dto.getIsMountain() != null ? dto.getIsMountain() : false)
                .distanceToCoastKm(dto.getDistanceToCoastKm())
                .isActive(dto.getIsActive() != null ? dto.getIsActive() : true)
                .isVolatile(dto.getIsVolatile() != null ? dto.getIsVolatile() : false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return toDto(weatherNodeRepository.save(entity));
    }

    public WeatherNodeDto updateWeatherNode(UUID id, WeatherNodeDto dto) {
        WeatherNode entity = weatherNodeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Weather Node not found"));

        entity.setCode(dto.getCode());
        entity.setGridKey(dto.getGridKey());
        entity.setLat(dto.getLat());
        entity.setLng(dto.getLng());
        entity.setElevationM(dto.getElevationM());
        entity.setZoneDensity(dto.getZoneDensity());
        entity.setIsCoastal(dto.getIsCoastal());
        entity.setIsMountain(dto.getIsMountain());
        entity.setDistanceToCoastKm(dto.getDistanceToCoastKm());
        entity.setIsActive(dto.getIsActive());
        entity.setIsVolatile(dto.getIsVolatile());
        entity.setUpdatedAt(LocalDateTime.now());

        return toDto(weatherNodeRepository.save(entity));
    }

    public void deleteWeatherNode(UUID id) {
        if (!weatherNodeRepository.existsById(id)) {
            throw new RuntimeException("Weather Node not found");
        }
        weatherNodeRepository.deleteById(id);
    }

    private WeatherNodeDto toDto(WeatherNode entity) {
        return WeatherNodeDto.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .gridKey(entity.getGridKey())
                .lat(entity.getLat())
                .lng(entity.getLng())
                .elevationM(entity.getElevationM())
                .zoneDensity(entity.getZoneDensity())
                .isCoastal(entity.getIsCoastal())
                .isMountain(entity.getIsMountain())
                .distanceToCoastKm(entity.getDistanceToCoastKm())
                .isActive(entity.getIsActive())
                .isVolatile(entity.getIsVolatile())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public WeatherNodeLiveCache getLiveTelemetry(UUID weatherNodeId) {
        if (!weatherNodeRepository.existsById(weatherNodeId)) {
            throw new RuntimeException("Weather Node not found");
        }
        return weatherNodeLiveCacheRepository.findById(weatherNodeId)
                .orElseThrow(() -> new RuntimeException("Live telemetry not available for this node yet."));
    }

    public WeatherNodeTelemetrySummaryDto getLiveTelemetrySummary(UUID weatherNodeId) {
        WeatherNodeLiveCache cache = getLiveTelemetry(weatherNodeId);

        WeatherNodeTelemetrySummaryDto.WeatherNodeTelemetrySummaryDtoBuilder builder = WeatherNodeTelemetrySummaryDto
                .builder()
                .weatherNodeId(cache.getWeatherNodeId())
                .sourceApi(cache.getSourceApi())
                .fetchedAt(cache.getFetchedAt())
                .tempC(cache.getTempC())
                .apparentTempC(cache.getApparentTempC())
                .humidityPct(cache.getHumidityPct())
                .pressureHpa(cache.getPressureHpa())
                .precipitationMm(cache.getPrecipitationMm())
                .precipProbability(cache.getPrecipProbability())
                .windSpeedKmh(cache.getWindSpeedKmh())
                .windGustKmh(cache.getWindGustKmh())
                .windDirectionDeg(cache.getWindDirectionDeg())
                .cloudCoverPct(cache.getCloudCoverPct())
                .visibilityM(cache.getVisibilityM())
                .uvIndex(cache.getUvIndex())
                .capeJkg(cache.getCapeJkg())
                .weatherCode(cache.getWeatherCode())
                .symbolCode(cache.getSymbolCode())
                .usAqi(cache.getUsAqi())
                .pm10(cache.getPm10())
                .pm25(cache.getPm25());

        try {
            if (cache.getRawPayload() == null || cache.getRawPayload().isBlank()) {
                builder.rawPayloadType("EMPTY").rawPayloadEntryCount(0);
                return builder.build();
            }

            JsonNode root = objectMapper.readTree(cache.getRawPayload());
            if (root.isArray()) {
                builder.rawPayloadType("ARRAY").rawPayloadEntryCount(root.size());
                if (!root.isEmpty()) {
                    JsonNode first = root.get(0);
                    JsonNode current = first.get("current");
                    if (current != null) {
                        builder.firstEntryTime(asText(current, "time"));
                        builder.firstEntryTempC(asDouble(current, "temperature_2m"));
                        builder.firstEntryHumidityPct(asDouble(current, "relative_humidity_2m"));
                        builder.firstEntryWeatherCode(asInt(current, "weather_code"));
                    }
                }
            } else if (root.isObject()) {
                builder.rawPayloadType("OBJECT").rawPayloadEntryCount(1);
                JsonNode current = root.get("current");
                if (current != null) {
                    builder.firstEntryTime(asText(current, "time"));
                    builder.firstEntryTempC(asDouble(current, "temperature_2m"));
                    builder.firstEntryHumidityPct(asDouble(current, "relative_humidity_2m"));
                    builder.firstEntryWeatherCode(asInt(current, "weather_code"));
                }
            } else {
                builder.rawPayloadType("UNKNOWN").rawPayloadEntryCount(0);
            }
        } catch (Exception ignored) {
            builder.rawPayloadType("INVALID_JSON").rawPayloadEntryCount(0);
        }

        return builder.build();
    }

    private static String asText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Double asDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static Integer asInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }
}
