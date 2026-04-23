-- =============================================================================
-- V14 — JAXA Rain Grid Data Integrity Fixes
-- Fixes: unique constraint for dedup, default product_type, composite index,
--        rainfall_mm precision, and product_type length
-- =============================================================================

-- 1. Add unique constraint to prevent duplicate rows on re-ingest
--    (supports ON CONFLICT DO NOTHING in JaxaGsmapClient.bulkInsert)
CREATE UNIQUE INDEX IF NOT EXISTS uq_jaxa_rain_grid_time_lat_lon
    ON jaxa_rain_grid (timestamp_utc, grid_lat, grid_lon);

-- 2. Fix default product_type: was 'GSMaP_NOW', align to 'GSMaP_NRT' (most common product)
ALTER TABLE jaxa_rain_grid
    ALTER COLUMN product_type SET DEFAULT 'GSMaP_NRT';

-- 3. Widen rainfall_mm from DECIMAL(6,2) to DECIMAL(7,3) for light-rain precision
--    (values like 0.005 mm/hr were being rounded to 0.01 or 0.00)
ALTER TABLE jaxa_rain_grid
    ALTER COLUMN rainfall_mm TYPE DECIMAL(7,3);

-- 4. Widen product_type from VARCHAR(20) to VARCHAR(30) to fit 'GSMaP_Gauge_NRT' etc.
ALTER TABLE jaxa_rain_grid
    ALTER COLUMN product_type TYPE VARCHAR(30);

-- 5. Add composite index for the primary query pattern used by WeatherService
--    (grid_lat, grid_lon, timestamp_utc DESC)
CREATE INDEX IF NOT EXISTS idx_jaxa_lat_lon_time
    ON jaxa_rain_grid (grid_lat, grid_lon, timestamp_utc DESC);
