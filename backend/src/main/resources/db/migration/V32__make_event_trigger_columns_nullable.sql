-- Migration: Make rule_id and spatial_unit_id nullable in event_triggers
-- Reason: SOS events don't have a source rule_id and may not resolve to a spatial_unit_id

ALTER TABLE event_triggers
    ALTER COLUMN rule_id DROP NOT NULL;

ALTER TABLE event_triggers
    ALTER COLUMN spatial_unit_id DROP NOT NULL;
