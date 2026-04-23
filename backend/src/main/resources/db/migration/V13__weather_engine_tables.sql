-- =============================================================================
-- V13 — Weather Engine Tables
-- ClimaSphere / SIDMS Backend
-- Creates: station_metadata, station_observations, bias_history,
--          gn_station_anchors, jaxa_rain_grid, node_timeseries,
--          sync_state, openmeteo_forecast_ensemble
-- Alters:  weather_nodes, spatial_units, spatial_unit_weather_node_mappings
-- Seeds:   sync_state (job names), station_metadata (real Sri Lanka stations)
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. station_metadata
--    Master registry for all physical observation stations
--    (Meteo.gov.lk Excel stations + NOAA METAR airports)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS station_metadata (
    station_id      VARCHAR(20)     PRIMARY KEY,
    station_name    VARCHAR(100)    NOT NULL,
    station_type    VARCHAR(20)     NOT NULL,          -- METEO_GOV_LK | NOAA_METAR
    latitude        DECIMAL(9,6)    NOT NULL,
    longitude       DECIMAL(9,6)    NOT NULL,
    elevation_m     DECIMAL(6,1)
);

-- ---------------------------------------------------------------------------
-- 2. station_observations
--    Raw ingested readings from every station source
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS station_observations (
    id              BIGSERIAL       PRIMARY KEY,
    station_id      VARCHAR(20)     NOT NULL,
    timestamp_utc   TIMESTAMP       NOT NULL,
    temperature_c   DECIMAL(5,2),
    rainfall_mm     DECIMAL(6,2),
    humidity_pct    DECIMAL(5,2),
    wind_speed_ms   DECIMAL(5,2),
    weather_type    VARCHAR(50),
    source          VARCHAR(20)     NOT NULL,
    raw_data        TEXT,
    created_at      TIMESTAMP       DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_station_obs_station_time
    ON station_observations (station_id, timestamp_utc DESC);

-- ---------------------------------------------------------------------------
-- 3. bias_history
--    Rolling bias correction history per weather_node per variable
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bias_history (
    id              BIGSERIAL       PRIMARY KEY,
    node_id         UUID            NOT NULL
                        REFERENCES weather_nodes(id) ON DELETE CASCADE,
    variable        VARCHAR(20)     NOT NULL,          -- temp | humidity | wind
    bias_value      DECIMAL(8,4)    NOT NULL,
    timestamp_utc   TIMESTAMP       NOT NULL,
    created_at      TIMESTAMP       DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bias_node_time
    ON bias_history (node_id, timestamp_utc DESC);

-- ---------------------------------------------------------------------------
-- 4. gn_station_anchors
--    GN divisions within 2 km of a physical station are served directly
--    (no IDW interpolation) — composite PK prevents duplicates
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gn_station_anchors (
    gn_id           UUID            NOT NULL
                        REFERENCES spatial_units(id) ON DELETE CASCADE,
    station_id      VARCHAR(20)     NOT NULL,
    distance_km     DECIMAL(5,2),
    PRIMARY KEY (gn_id, station_id)
);

-- ---------------------------------------------------------------------------
-- 5. jaxa_rain_grid
--    Hourly GSMaP satellite rainfall grid points ingested from JAXA FTP
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS jaxa_rain_grid (
    id              BIGSERIAL       PRIMARY KEY,
    timestamp_utc   TIMESTAMP       NOT NULL,
    grid_lat        DECIMAL(8,6)    NOT NULL,
    grid_lon        DECIMAL(9,6)    NOT NULL,
    rainfall_mm     DECIMAL(6,2)    NOT NULL,
    product_type    VARCHAR(20)     DEFAULT 'GSMaP_NOW'
);

CREATE INDEX IF NOT EXISTS idx_jaxa_time
    ON jaxa_rain_grid (timestamp_utc DESC);

-- ---------------------------------------------------------------------------
-- 6. node_timeseries
--    IDW-interpolated hourly forecast timeseries per weather node
--    Replaces the deprecated weather_node_hourly_forecast table
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS node_timeseries (
    id                  BIGSERIAL       PRIMARY KEY,
    node_id             UUID            NOT NULL
                            REFERENCES weather_nodes(id) ON DELETE CASCADE,
    valid_from_utc      TIMESTAMP       NOT NULL,
    forecast_hour       INTEGER         NOT NULL,
    temperature_c       DECIMAL(5,2),
    humidity_pct        DECIMAL(5,2),
    wind_speed_ms       DECIMAL(5,2),
    wind_direction_deg  INTEGER,
    pressure_hpa        DECIMAL(6,1),
    precipitation_mm    DECIMAL(6,2),
    weather_code        INTEGER,
    created_at          TIMESTAMP       DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ts_node_time
    ON node_timeseries (node_id, valid_from_utc);

-- ---------------------------------------------------------------------------
-- 7. sync_state
--    Scheduler cooldown / health tracking — job_name is the PK (String @Id)
--    manual_override bypasses shouldRun() cooldown gate
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sync_state (
    job_name            VARCHAR(100)    PRIMARY KEY,
    last_success_utc    TIMESTAMP,
    next_allowed_utc    TIMESTAMP,
    manual_override     BOOLEAN         DEFAULT FALSE,
    error_count         INTEGER         DEFAULT 0,
    last_error          TEXT,
    updated_at          TIMESTAMP       DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- 8. openmeteo_forecast_ensemble
--    14-day daily ensemble percentile forecasts from Open-Meteo
--    UNIQUE on (node_id, forecast_date) — upsert-safe
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS openmeteo_forecast_ensemble (
    id                          BIGSERIAL       PRIMARY KEY,
    node_id                     UUID            NOT NULL
                                    REFERENCES weather_nodes(id) ON DELETE CASCADE,
    forecast_date               DATE            NOT NULL,
    temp_min_p10                DECIMAL(5,2),
    temp_min_p50                DECIMAL(5,2),
    temp_min_p90                DECIMAL(5,2),
    temp_max_p10                DECIMAL(5,2),
    temp_max_p50                DECIMAL(5,2),
    temp_max_p90                DECIMAL(5,2),
    precipitation_probability   DECIMAL(5,4),
    created_at                  TIMESTAMP       DEFAULT NOW(),
    UNIQUE (node_id, forecast_date)
);

-- =============================================================================
-- ALTER existing tables — all use IF NOT EXISTS so re-runs are idempotent
-- =============================================================================

-- weather_nodes: add elevation + bias correction columns used by IDW engine
ALTER TABLE weather_nodes ADD COLUMN IF NOT EXISTS elevation_m          DECIMAL(6,1) DEFAULT 0;
ALTER TABLE weather_nodes ADD COLUMN IF NOT EXISTS bias_temp_c          DECIMAL(5,2) DEFAULT 0;
ALTER TABLE weather_nodes ADD COLUMN IF NOT EXISTS bias_humidity_pct    DECIMAL(5,2) DEFAULT 0;
ALTER TABLE weather_nodes ADD COLUMN IF NOT EXISTS bias_wind_ms         DECIMAL(5,2) DEFAULT 0;

-- spatial_units: elevation used for 3D effective-distance IDW
ALTER TABLE spatial_units ADD COLUMN IF NOT EXISTS elevation_m          DECIMAL(6,1) DEFAULT 0;

-- spatial_unit_weather_node_mappings: effective distance + elevation delta
ALTER TABLE spatial_unit_weather_node_mappings
    ADD COLUMN IF NOT EXISTS effective_distance     DECIMAL(8,3);
ALTER TABLE spatial_unit_weather_node_mappings
    ADD COLUMN IF NOT EXISTS elevation_diff_m       DECIMAL(6,1);

-- =============================================================================
-- SEED: sync_state — all known scheduler job names
-- =============================================================================
INSERT INTO sync_state (job_name) VALUES
    ('yrno_sync'),
    ('openmeteo_forecast_sync'),
    ('meteo_gov_lk_sync'),
    ('noaa_metar_sync'),
    ('jaxa_gsmap_sync'),
    ('bias_recalc_sync'),
    ('weather_sync'),
    ('flood_sync'),
    ('meteo_content_sync'),
    ('cache_warming'),
    ('historical_backfill'),
    ('alert_evaluation')
ON CONFLICT DO NOTHING;

-- =============================================================================
-- SEED: station_metadata
--    24 Meteo.gov.lk physical stations (numeric IDs from Excel workbook)
--    + 2 active NOAA METAR stations (VCBI, VCRI)
-- =============================================================================
INSERT INTO station_metadata (station_id, station_name, station_type, latitude, longitude, elevation_m) VALUES
    ('43404',  'JAFFNA',                 'METEO_GOV_LK',  9.6938245,  80.0329471,   3.0),
    ('43410',  'MULLATIVU',              'METEO_GOV_LK',  9.270556,   80.8192387,   3.0),
    ('43413',  'MANNAR',                 'METEO_GOV_LK',  8.9870577,  79.9076095,   4.0),
    ('43415',  'VAVUNIYA',               'METEO_GOV_LK',  8.7599782,  80.4965909,  98.0),
    ('43418',  'TRINCOMALEE',            'METEO_GOV_LK',  8.6398269,  81.2027248,  24.0),
    ('43421',  'ANURADHAPURA',           'METEO_GOV_LK',  8.335,      80.415,       92.0),
    ('43422',  'MAHA ILLUPPALLAMA',      'METEO_GOV_LK',  8.110271,   80.4670389,  117.0),
    ('43424',  'PUTTALAM',               'METEO_GOV_LK',  8.0270307,  79.8412733,   2.0),
    ('43436',  'BATTICALOA',             'METEO_GOV_LK',  7.72,       81.7,          8.0),
    ('43441',  'KURUNEGALA',             'METEO_GOV_LK',  7.4794462,  80.3534561,  116.0),
    ('43444',  'KATUGASTOTA',            'METEO_GOV_LK',  7.334448,   80.6264166,  417.0),
    ('43450',  'KATUNAYAKE',             'METEO_GOV_LK',  7.1929923,  79.8964795,   8.0),
    ('43466',  'COLOMBO',                'METEO_GOV_LK',  6.9050433,  79.8720277,   7.0),
    ('43467',  'RATMALANA',              'METEO_GOV_LK',  6.82108,    79.88861,      5.0),
    ('43473',  'NUWARA ELIYA',           'METEO_GOV_LK',  6.9695314,  80.7791524, 1894.0),
    ('43476',  'BANDARAWELA',            'METEO_GOV_LK',  6.8309042,  80.9862597, 1225.0),
    ('43479',  'BADULLA',                'METEO_GOV_LK',  6.9833652,  81.048616,   670.0),
    ('43486',  'RATNAPURA',              'METEO_GOV_LK',  6.7131709,  80.3808115,   86.0),
    ('43495',  'GALLE',                  'METEO_GOV_LK',  6.0297239,  80.214594,    12.0),
    ('43497',  'HAMBANTOTA',             'METEO_GOV_LK',  6.1224829,  81.1287286,   16.0),
    ('43475',  'POTTUVIL',               'METEO_GOV_LK',  6.88,       81.83,         4.0),
    ('330601', 'MATTALA',                'METEO_GOV_LK',  6.3,        81.13,         61.0),
    ('821501', 'MONARAGALA',             'METEO_GOV_LK',  6.8355124,  81.3156141,  165.0),
    ('721501', 'POLONNARUWA',            'METEO_GOV_LK',  7.913077,   81.0499734,   43.0),
    ('VCBI',   'Katunayake Airport (VCBI)', 'NOAA_METAR', 7.1808,    79.8841,        9.0),
    ('VCRI',   'Mattala Airport (VCRI)',    'NOAA_METAR', 6.2844,    81.1247,       61.0)
ON CONFLICT DO NOTHING;
