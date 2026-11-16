-- Liquibase migration 007: Add decision_gate_json JSONB column to incidents
ALTER TABLE incidents
    ADD COLUMN IF NOT EXISTS decision_gate_json JSONB;
