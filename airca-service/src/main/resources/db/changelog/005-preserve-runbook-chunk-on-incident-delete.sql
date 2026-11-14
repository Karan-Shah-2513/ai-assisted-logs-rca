-- Drop existing foreign key on source_incident_id
-- and recreate it with ON DELETE SET NULL to preserve knowledge chunks when incidents are purged.
DO $$
DECLARE
    fk_record RECORD;
BEGIN
    FOR fk_record IN
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
          AND tc.table_schema = kcu.table_schema
        WHERE tc.table_name = 'runbook_chunks'
          AND tc.constraint_type = 'FOREIGN KEY'
          AND kcu.column_name = 'source_incident_id'
    LOOP
        EXECUTE 'ALTER TABLE runbook_chunks DROP CONSTRAINT ' || quote_ident(fk_record.constraint_name);
    END LOOP;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'runbook_chunks'
          AND constraint_name = 'runbook_chunks_source_incident_id_fkey'
    ) THEN
        ALTER TABLE runbook_chunks
            ADD CONSTRAINT runbook_chunks_source_incident_id_fkey
            FOREIGN KEY (source_incident_id)
            REFERENCES incidents(id)
            ON DELETE SET NULL;
    END IF;
END $$;
