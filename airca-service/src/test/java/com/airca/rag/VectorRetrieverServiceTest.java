package com.airca.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.airca.ai.TextEmbeddingService;
import com.airca.model.Incident;
import com.airca.repository.RunbookChunkRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class VectorRetrieverServiceTest {

    private JdbcTemplate jdbcTemplate;
    private RunbookChunkRepository runbookChunkRepository;
    private TextEmbeddingService embeddingService;
    private GitCommitInspectorService gitCommitInspectorService;
    private VectorRetrieverService retrieverService;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        runbookChunkRepository = mock(RunbookChunkRepository.class);
        embeddingService = mock(TextEmbeddingService.class);
        gitCommitInspectorService = mock(GitCommitInspectorService.class);

        retrieverService = new VectorRetrieverService(
                jdbcTemplate,
                runbookChunkRepository,
                embeddingService,
                gitCommitInspectorService,
                0.72);
    }

    @Test
    void defaultMinSimilarityIsConfiguredCorrectly() {
        assertEquals(0.72, retrieverService.getMinSimilarity(), 0.0001);

        VectorRetrieverService defaultService = new VectorRetrieverService(
                jdbcTemplate, runbookChunkRepository, embeddingService, gitCommitInspectorService);
        assertEquals(0.72, defaultService.getMinSimilarity(), 0.0001);
    }

    @Test
    void ensureRunbooksIndexedPerformsLazyIndexingForUnembeddedRows() {
        UUID chunkId = UUID.randomUUID();
        String chunkText = "Database connection pool deadlock runbook";
        List<Double> dummyVector = Collections.nCopies(768, 0.05);
        String vectorLiteral = "[0.05,0.05]";

        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains("embedding IS NULL")),
                eq(tenantId)))
                .thenReturn(List.of(Map.of("id", chunkId, "chunk_text", chunkText)));

        when(embeddingService.embed(chunkText)).thenReturn(dummyVector);
        when(embeddingService.toVectorLiteral(dummyVector)).thenReturn(vectorLiteral);

        retrieverService.ensureRunbooksIndexed(tenantId);

        verify(embeddingService).embed(chunkText);
        verify(embeddingService).toVectorLiteral(dummyVector);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains("UPDATE runbook_chunks")),
                eq(vectorLiteral),
                eq(chunkId),
                eq(tenantId));
    }

    @Test
    void ensureRunbooksIndexedDoesNotSwallowIndexingFailures() {
        UUID chunkId = UUID.randomUUID();
        String chunkText = "Payment gateway timeout runbook";

        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains("embedding IS NULL")),
                eq(tenantId)))
                .thenReturn(List.of(Map.of("id", chunkId, "chunk_text", chunkText)));

        when(embeddingService.embed(chunkText))
                .thenThrow(new IllegalStateException("GEMINI_API_KEY must be configured"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> retrieverService.ensureRunbooksIndexed(tenantId));

        assertTrue(ex.getMessage().contains("GEMINI_API_KEY"));
        verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains("UPDATE runbook_chunks")), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieveContextConstructsCosineQueriesWithThresholdAndFormatsSimilarityScores() {
        Incident incident = new Incident();
        incident.setTenantId(tenantId);
        incident.setServiceName("order-service");
        incident.setSanitizedStackTrace("java.lang.RuntimeException: checkout timeout");

        List<Double> queryVec = Collections.nCopies(768, 0.01);
        String queryVecLiteral = "[0.01,0.01]";
        when(embeddingService.embed(anyString())).thenReturn(queryVec);
        when(embeddingService.toVectorLiteral(queryVec)).thenReturn(queryVecLiteral);
        when(gitCommitInspectorService.findRelevantDiff(anyString(), anyString())).thenReturn("diff --git a/Order.java");

        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains("embedding IS NULL")),
                eq(tenantId)))
                .thenReturn(Collections.emptyList());

        VectorRetrieverService.RunbookMatch runbookMatch = new VectorRetrieverService.RunbookMatch(
                "Timeout Runbook",
                "Increase timeout and restart consumer",
                "docs/timeout.md",
                "+ timeout = 30s",
                0.15,
                0.85);

        VectorRetrieverService.ResolvedIncidentMatch incidentMatch = new VectorRetrieverService.ResolvedIncidentMatch(
                "order-service",
                "TimeoutException in checkout",
                3,
                Instant.parse("2026-11-08T10:00:00Z"),
                0.20,
                0.80);

        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains("runbook_chunks")),
                any(RowMapper.class),
                eq(queryVecLiteral),
                eq(queryVecLiteral),
                eq(tenantId),
                eq(queryVecLiteral),
                eq(0.72),
                eq(queryVecLiteral)))
                .thenReturn(List.of(runbookMatch));

        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains("FROM incidents")),
                any(RowMapper.class),
                eq(queryVecLiteral),
                eq(queryVecLiteral),
                eq(tenantId),
                eq(queryVecLiteral),
                eq(0.72),
                eq(queryVecLiteral)))
                .thenReturn(List.of(incidentMatch));

        String context = retrieverService.retrieveContext(incident);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(queryVecLiteral),
                eq(queryVecLiteral),
                eq(tenantId),
                eq(queryVecLiteral),
                eq(0.72),
                eq(queryVecLiteral));

        List<String> capturedSqls = sqlCaptor.getAllValues();
        assertTrue(capturedSqls.stream().anyMatch(sql ->
                sql.contains("runbook_chunks")
                        && sql.contains("embedding <=> ?::vector")
                        && sql.contains("(1.0 - (embedding <=> ?::vector)) >= ?")
                        && sql.contains("ORDER BY embedding <=> ?::vector ASC")));

        assertTrue(capturedSqls.stream().anyMatch(sql ->
                sql.contains("incidents")
                        && sql.contains("status = 'RESOLVED'")
                        && sql.contains("embedding <=> ?::vector")
                        && sql.contains("(1.0 - (embedding <=> ?::vector)) >= ?")
                        && sql.contains("ORDER BY embedding <=> ?::vector ASC")));

        assertTrue(context.contains("Vector-matched runbook (similarity 0.8500, cosine distance 0.1500): Timeout Runbook"));
        assertTrue(context.contains("Previously resolved incident (similarity 0.8000, cosine distance 0.2000): order-service"));
        assertTrue(context.contains("Relevant git change:"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieveContextHasNoLexicalFallbackWhenVectorSearchReturnsNoMatches() {
        Incident incident = new Incident();
        incident.setTenantId(tenantId);
        incident.setServiceName("ledger-service");
        incident.setSanitizedStackTrace("org.postgresql.util.PSQLException: deadlock detected");

        List<Double> queryVec = Collections.nCopies(768, 0.02);
        String queryVecLiteral = "[0.02,0.02]";
        when(embeddingService.embed(anyString())).thenReturn(queryVec);
        when(embeddingService.toVectorLiteral(queryVec)).thenReturn(queryVecLiteral);
        when(gitCommitInspectorService.findRelevantDiff(anyString(), anyString())).thenReturn("diff --git a/Ledger.java");

        when(jdbcTemplate.queryForList(
                contains("SELECT id, chunk_text FROM runbook_chunks WHERE tenant_id = ? AND embedding IS NULL"),
                eq(tenantId)))
                .thenReturn(Collections.emptyList());

        when(jdbcTemplate.query(
                contains("FROM runbook_chunks"),
                any(RowMapper.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        when(jdbcTemplate.query(
                contains("FROM incidents"),
                any(RowMapper.class),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        String context = retrieverService.retrieveContext(incident);

        verify(runbookChunkRepository, never()).findAllByTenantIdOrderByCreatedAtDesc(any());
        assertFalse(context.contains("Vector-matched runbook"));
        assertFalse(context.contains("Previously resolved incident"));
        assertTrue(context.startsWith("Relevant git change:\n"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieve_returnsStructuredResultWithTopQualifyingRunbookCandidate() {
        Incident incident = new Incident();
        incident.setTenantId(tenantId);
        incident.setServiceName("order-service");
        incident.setSanitizedStackTrace("java.lang.RuntimeException: checkout timeout");

        List<Double> queryVec = Collections.nCopies(768, 0.01);
        String queryVecLiteral = "[0.01,0.01]";
        when(embeddingService.embed(anyString())).thenReturn(queryVec);
        when(embeddingService.toVectorLiteral(queryVec)).thenReturn(queryVecLiteral);
        when(gitCommitInspectorService.findRelevantDiff(anyString(), anyString())).thenReturn("diff --git a/Order.java");

        when(jdbcTemplate.queryForList(anyString(), eq(tenantId)))
                .thenReturn(Collections.emptyList());

        VectorRetrieverService.RunbookMatch topMatch = new VectorRetrieverService.RunbookMatch(
                "Order Checkout Timeout Runbook",
                "Increase timeout and restart worker",
                "docs/timeout.md",
                "+ timeout = 30s",
                0.10,
                0.90);
        VectorRetrieverService.RunbookMatch secondMatch = new VectorRetrieverService.RunbookMatch(
                "Secondary Runbook",
                "Secondary text",
                "docs/sec.md",
                null,
                0.22,
                0.78);

        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains("runbook_chunks")),
                any(RowMapper.class),
                eq(queryVecLiteral),
                eq(queryVecLiteral),
                eq(tenantId),
                eq(queryVecLiteral),
                eq(0.72),
                eq(queryVecLiteral)))
                .thenReturn(List.of(topMatch, secondMatch));

        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.argThat(sql -> sql != null && sql.contains("FROM incidents")),
                any(RowMapper.class),
                eq(queryVecLiteral),
                eq(queryVecLiteral),
                eq(tenantId),
                eq(queryVecLiteral),
                eq(0.72),
                eq(queryVecLiteral)))
                .thenReturn(Collections.emptyList());

        RetrievalResult result = retrieverService.retrieve(incident);

        assertEquals(2, result.runbookMatches().size());
        assertTrue(result.topRunbookMatch().isPresent());
        assertEquals("Order Checkout Timeout Runbook", result.topRunbookMatch().get().runbookName());
        assertEquals("Order Checkout Timeout Runbook", result.topRunbookName().orElse(null));
        assertTrue(result.hasQualifyingMatch());
        assertTrue(result.context().contains("Order Checkout Timeout Runbook"));
        assertEquals(result.context(), retrieverService.retrieveContext(incident));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieve_returnsStructuredResultWithoutMatchesWhenBelowThreshold() {
        Incident incident = new Incident();
        incident.setTenantId(tenantId);
        incident.setServiceName("order-service");
        incident.setSanitizedStackTrace("java.lang.RuntimeException: unknown error");

        List<Double> queryVec = Collections.nCopies(768, 0.01);
        String queryVecLiteral = "[0.01,0.01]";
        when(embeddingService.embed(anyString())).thenReturn(queryVec);
        when(embeddingService.toVectorLiteral(queryVec)).thenReturn(queryVecLiteral);
        when(gitCommitInspectorService.findRelevantDiff(anyString(), anyString())).thenReturn("");

        when(jdbcTemplate.queryForList(anyString(), eq(tenantId)))
                .thenReturn(Collections.emptyList());

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        RetrievalResult result = retrieverService.retrieve(incident);

        assertTrue(result.runbookMatches().isEmpty());
        assertTrue(result.topRunbookMatch().isEmpty());
        assertTrue(result.topRunbookName().isEmpty());
        assertFalse(result.hasQualifyingMatch());
        assertEquals(result.context(), retrieverService.retrieveContext(incident));
    }

    @Test
    void indexResolvedIncident_generatesEmbeddingAndUpsertsWithHumanVerifiedIncidentSource() {
        UUID incidentId = UUID.randomUUID();
        String runbookName = "Resolved incident order-service " + incidentId;
        String resolutionText = "Verified root cause: DB pool leak\nVerified fix: Restart";
        List<Double> dummyVector = Collections.nCopies(768, 0.03);
        String vectorLiteral = "[0.03,0.03]";

        when(embeddingService.embed(resolutionText)).thenReturn(dummyVector);
        when(embeddingService.toVectorLiteral(dummyVector)).thenReturn(vectorLiteral);

        retrieverService.indexResolvedIncident(tenantId, incidentId, runbookName, resolutionText);

        verify(embeddingService).embed(resolutionText);
        verify(embeddingService).toVectorLiteral(dummyVector);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
                sqlCaptor.capture(),
                eq(tenantId),
                eq(runbookName),
                eq(resolutionText),
                eq("resolved-incidents/" + runbookName),
                eq("HUMAN_VERIFIED_INCIDENT"),
                eq(incidentId),
                eq(vectorLiteral));

        String capturedSql = sqlCaptor.getValue();
        assertTrue(capturedSql.contains("INSERT INTO runbook_chunks"));
        assertTrue(capturedSql.contains("source"));
        assertTrue(capturedSql.contains("source_incident_id"));
        assertTrue(capturedSql.contains("ON CONFLICT (tenant_id, source_incident_id) DO UPDATE SET"));
        assertTrue(capturedSql.contains("chunk_text = EXCLUDED.chunk_text"));
        assertTrue(capturedSql.contains("source = EXCLUDED.source"));
        assertTrue(capturedSql.contains("embedding = EXCLUDED.embedding"));
    }

    @Test
    void indexResolvedIncident_propagatesEmbeddingException_causingRollback() {
        UUID incidentId = UUID.randomUUID();
        String runbookName = "Resolved incident order-service " + incidentId;
        String resolutionText = "Verified root cause: DB pool leak";

        when(embeddingService.embed(resolutionText))
                .thenThrow(new IllegalStateException("Gemini embedding failure"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> retrieverService.indexResolvedIncident(tenantId, incidentId, runbookName, resolutionText));

        assertTrue(ex.getMessage().contains("Gemini embedding failure"));
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void indexResolvedIncident_truncatesLongRunbookNamesTo240Characters() {
        UUID incidentId = UUID.randomUUID();
        String veryLongName = "A".repeat(300);
        String resolutionText = "Short text";
        List<Double> dummyVector = Collections.nCopies(768, 0.01);
        String vectorLiteral = "[0.01,0.01]";

        when(embeddingService.embed(resolutionText)).thenReturn(dummyVector);
        when(embeddingService.toVectorLiteral(dummyVector)).thenReturn(vectorLiteral);

        retrieverService.indexResolvedIncident(tenantId, incidentId, veryLongName, resolutionText);

        String expectedTruncated = "A".repeat(240);
        verify(jdbcTemplate).update(
                anyString(),
                eq(tenantId),
                eq(expectedTruncated),
                eq(resolutionText),
                eq("resolved-incidents/" + expectedTruncated),
                eq("HUMAN_VERIFIED_INCIDENT"),
                eq(incidentId),
                eq(vectorLiteral));
    }
}
