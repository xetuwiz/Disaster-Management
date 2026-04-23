-- Add new comprehensive weather fields to node_timeseries

ALTER TABLE node_timeseries
    ADD COLUMN cloud_cover_pct DECIMAL(5, 2),
    ADD COLUMN cloud_cover_high_pct DECIMAL(5, 2),
    ADD COLUMN cloud_cover_low_pct DECIMAL(5, 2),
    ADD COLUMN cloud_cover_medium_pct DECIMAL(5, 2),
    ADD COLUMN dew_point_c DECIMAL(5, 2),
    ADD COLUMN fog_area_fraction_pct DECIMAL(5, 2),
    ADD COLUMN uv_index_clear_sky DECIMAL(5, 2),
    ADD COLUMN wind_gust_ms DECIMAL(5, 2),
    ADD COLUMN temp_percentile_10 DECIMAL(5, 2),
    ADD COLUMN temp_percentile_90 DECIMAL(5, 2),
    ADD COLUMN wind_percentile_10 DECIMAL(5, 2),
    ADD COLUMN wind_percentile_90 DECIMAL(5, 2),
    ADD COLUMN precip_prob_pct DECIMAL(5, 2),
    ADD COLUMN thunder_prob_pct DECIMAL(5, 2),
    ADD COLUMN precip_min_mm DECIMAL(6, 2),
    ADD COLUMN precip_max_mm DECIMAL(6, 2),
    ADD COLUMN temp_max_c DECIMAL(5, 2),
    ADD COLUMN temp_min_c DECIMAL(5, 2),
    ADD COLUMN symbol_code VARCHAR(50);

ALTER TABLE weather_node_live_cache
    ADD COLUMN symbol_code VARCHAR(50);
