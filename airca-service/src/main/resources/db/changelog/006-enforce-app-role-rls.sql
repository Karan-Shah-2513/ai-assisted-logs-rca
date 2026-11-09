-- Liquibase migration 006: Enforce PostgreSQL RLS with dedicated NOBYPASSRLS application role

-- 1. Idempotently create or alter the runtime application login role
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${app_user}') THEN
        CREATE ROLE "${app_user}" WITH LOGIN PASSWORD '${app_password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
    ELSE
        ALTER ROLE "${app_user}" WITH LOGIN PASSWORD '${app_password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
    END IF;
END $$;

-- 2. Grant schema usage to the runtime role
GRANT USAGE ON SCHEMA public TO "${app_user}";

-- 3. Grant read-only access to tenant registry
GRANT SELECT ON TABLE tenants TO "${app_user}";

-- 4. Grant required CRUD permissions on protected application domain tables
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE incidents, runbook_chunks TO "${app_user}";

-- 5. Grant sequence privileges for any sequences in public schema
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${app_user}";

-- 6. Configure default privileges for future tables/sequences created by owner
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${app_user}";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO "${app_user}";

-- 7. Ensure Row-Level Security is enabled and FORCED on protected tables
ALTER TABLE incidents ENABLE ROW LEVEL SECURITY;
ALTER TABLE incidents FORCE ROW LEVEL SECURITY;

ALTER TABLE runbook_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE runbook_chunks FORCE ROW LEVEL SECURITY;

-- 8. Idempotently ensure tenant isolation policies exist on protected tables
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'incidents' AND policyname = 'incidents_tenant_isolation'
    ) THEN
        CREATE POLICY incidents_tenant_isolation ON incidents
            USING (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''))
            WITH CHECK (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'runbook_chunks' AND policyname = 'runbook_chunks_tenant_isolation'
    ) THEN
        CREATE POLICY runbook_chunks_tenant_isolation ON runbook_chunks
            USING (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''))
            WITH CHECK (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''));
    END IF;
END $$;
