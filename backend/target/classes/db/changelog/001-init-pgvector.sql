CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    fingerprint_hash VARCHAR(64) NOT NULL,
    service_name VARCHAR(200) NOT NULL,
    raw_stack_trace TEXT NOT NULL,
    sanitized_stack_trace TEXT NOT NULL,
    occurrence_count BIGINT NOT NULL DEFAULT 1 CHECK (occurrence_count > 0),
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    ai_rca_json JSONB,
    first_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT incidents_tenant_fingerprint_uq UNIQUE (tenant_id, fingerprint_hash),
    CONSTRAINT incidents_status_ck CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED'))
);

CREATE INDEX incidents_tenant_last_seen_idx ON incidents (tenant_id, last_seen DESC);
CREATE INDEX incidents_tenant_status_idx ON incidents (tenant_id, status);

CREATE TABLE runbook_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    runbook_name VARCHAR(240) NOT NULL,
    chunk_text TEXT NOT NULL,
    source_path VARCHAR(500),
    git_diff TEXT,
    embedding VECTOR(1536),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX runbook_chunks_tenant_idx ON runbook_chunks (tenant_id);
CREATE INDEX runbook_chunks_embedding_idx ON runbook_chunks USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

ALTER TABLE incidents ENABLE ROW LEVEL SECURITY;
ALTER TABLE incidents FORCE ROW LEVEL SECURITY;
CREATE POLICY incidents_tenant_isolation ON incidents
    USING (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''))
    WITH CHECK (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''));

ALTER TABLE runbook_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE runbook_chunks FORCE ROW LEVEL SECURITY;
CREATE POLICY runbook_chunks_tenant_isolation ON runbook_chunks
    USING (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''))
    WITH CHECK (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''));
