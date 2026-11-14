DROP INDEX IF EXISTS runbook_chunks_embedding_idx;

ALTER TABLE runbook_chunks ALTER COLUMN embedding TYPE vector(768) USING NULL;

ALTER TABLE incidents ADD COLUMN IF NOT EXISTS embedding vector(768);

CREATE INDEX IF NOT EXISTS idx_runbook_embedding_hnsw ON runbook_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_incidents_embedding_hnsw ON incidents USING hnsw (embedding vector_cosine_ops);
