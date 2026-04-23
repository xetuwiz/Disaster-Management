CREATE TABLE weather_node_celestial (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    weather_node_id UUID NOT NULL REFERENCES weather_nodes(id) ON DELETE CASCADE,
    record_date DATE NOT NULL,
    
    -- Sun events
    sunrise_time TIMESTAMP WITH TIME ZONE,
    sunrise_azimuth DOUBLE PRECISION,
    sunset_time TIMESTAMP WITH TIME ZONE,
    sunset_azimuth DOUBLE PRECISION,
    solarnoon_time TIMESTAMP WITH TIME ZONE,
    solarnoon_elevation DOUBLE PRECISION,
    solarmidnight_time TIMESTAMP WITH TIME ZONE,
    solarmidnight_elevation DOUBLE PRECISION,
    
    -- Moon events
    moonrise_time TIMESTAMP WITH TIME ZONE,
    moonrise_azimuth DOUBLE PRECISION,
    moonset_time TIMESTAMP WITH TIME ZONE,
    moonset_azimuth DOUBLE PRECISION,
    high_moon_time TIMESTAMP WITH TIME ZONE,
    high_moon_elevation DOUBLE PRECISION,
    low_moon_time TIMESTAMP WITH TIME ZONE,
    low_moon_elevation DOUBLE PRECISION,
    moonphase DOUBLE PRECISION,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE(weather_node_id, record_date)
);

CREATE INDEX idx_weather_node_celestial_node_date ON weather_node_celestial(weather_node_id, record_date);
