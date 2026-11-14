ALTER TABLE runbook_chunks
    ADD COLUMN IF NOT EXISTS source VARCHAR(64) NOT NULL DEFAULT 'RUNBOOK',
    ADD COLUMN IF NOT EXISTS source_incident_id UUID REFERENCES incidents(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX IF NOT EXISTS runbook_chunks_tenant_source_incident_uq
    ON runbook_chunks (tenant_id, source_incident_id);
