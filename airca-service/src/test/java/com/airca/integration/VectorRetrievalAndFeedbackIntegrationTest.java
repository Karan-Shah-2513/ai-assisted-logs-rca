package com.airca.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.airca.ai.AiRcaService;
import com.airca.ai.TextEmbeddingService;
import com.airca.controller.IncidentController;
import com.airca.controller.TelemetryService;
import com.airca.dto.IncidentDto;
import com.airca.dto.ResolveIncidentRequest;
import com.airca.model.Incident;
import com.airca.model.IncidentStatus;
import com.airca.model.RunbookChunk;
import com.airca.multitenancy.TenantContext;
import com.airca.rag.GitCommitInspectorService;
import com.airca.rag.VectorRetrieverService;
import com.airca.repository.IncidentRepository;
import com.airca.repository.RunbookChunkRepository;
import com.airca.repository.TenantRepository;
import com.airca.websocket.StompBroadcasterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 20 hermetic integration test verifying:
 * 1. PostgreSQL column enforcement of exact 768-dimensional embeddings.
 * 2. Real pgvector cosine ordering, similarity threshold filtering (>= 0.72), and tenant isolation.
 * 3. Lazy runbook embedding generation for unembedded chunks.
 * 4. Human-verified resolution feedback loop through IncidentController, ensuring indexed knowledge
 *    contains only human-verified root cause, fix, and resolver while excluding AI-generated suggestions.
 * 5. Preservation of human-verified knowledge chunks with ON DELETE SET NULL retention semantics (Task 12).
 * 6. Isolated Testcontainers execution disabled without Docker (not localhost).
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VectorRetrievalAndFeedbackIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static boolean postgresAvailable = false;
    private static JdbcTemplate appJdbcTemplate;
    private static DeterministicTestEmbeddingService testEmbeddingService;
    private static GitCommitInspectorService mockGitInspector;
    private static VectorRetrieverService vectorRetrieverService;
    private static IncidentController incidentController;
    private static IncidentRepository incidentRepository;
    private static StompBroadcasterService mockBroadcaster;

    @BeforeAll
    static void setUpEnvironment() {
        if (!isDockerAvailable()) {
            postgresAvailable = false;
            return;
        }

        if (!postgres.isRunning()) {
            postgres.start();
        }

        try (Connection conn = createMigrationConnection()) {
            postgresAvailable = conn != null && !conn.isClosed();
        } catch (Exception ex) {
            postgresAvailable = false;
            return;
        }

        try {
            runLiquibaseMigrations();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to run Liquibase migrations for Task 20 integration test", ex);
        }

        DriverManagerDataSource baseAppDataSource = new DriverManagerDataSource(
                getJdbcUrl(), getAppUser(), getAppPassword());
        TenantAwareDataSource tenantAwareDataSource = new TenantAwareDataSource(baseAppDataSource);
        appJdbcTemplate = new JdbcTemplate(tenantAwareDataSource);

        testEmbeddingService = new DeterministicTestEmbeddingService();
        mockGitInspector = mock(GitCommitInspectorService.class);
        when(mockGitInspector.findRelevantDiff(any(), any()))
                .thenReturn("No git diff detected for test scenario");

        RunbookChunkRepository mockRunbookChunkRepo = mock(RunbookChunkRepository.class);

        vectorRetrieverService = new VectorRetrieverService(
                appJdbcTemplate,
                mockRunbookChunkRepo,
                testEmbeddingService,
                mockGitInspector,
                0.72);

        incidentRepository = mock(IncidentRepository.class);
        when(incidentRepository.findByIdAndTenantId(any(UUID.class), any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            UUID tenantId = invocation.getArgument(1);
            List<Incident> results = appJdbcTemplate.query(
                    """
                    SELECT id, tenant_id, fingerprint_hash, service_name, raw_stack_trace,
                           sanitized_stack_trace, occurrence_count, status, ai_rca_json, first_seen, last_seen
                    FROM incidents
                    WHERE id = ? AND tenant_id = ?
                    """,
                    (rs, rowNum) -> {
                        Incident inc = new Incident();
                        inc.setId(rs.getObject("id", UUID.class));
                        inc.setTenantId(rs.getObject("tenant_id", UUID.class));
                        inc.setFingerprintHash(rs.getString("fingerprint_hash"));
                        inc.setServiceName(rs.getString("service_name"));
                        inc.setRawStackTrace(rs.getString("raw_stack_trace"));
                        inc.setSanitizedStackTrace(rs.getString("sanitized_stack_trace"));
                        inc.setOccurrenceCount(rs.getLong("occurrence_count"));
                        inc.setStatus(IncidentStatus.valueOf(rs.getString("status")));
                        String jsonStr = rs.getString("ai_rca_json");
                        if (jsonStr != null) {
                            try {
                                inc.setAiRcaJson(new ObjectMapper().readTree(jsonStr));
                            } catch (Exception ignored) {
                            }
                        }
                        inc.setFirstSeen(rs.getTimestamp("first_seen").toInstant());
                        inc.setLastSeen(rs.getTimestamp("last_seen").toInstant());
                        return inc;
                    },
                    id, tenantId);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        });

        when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> {
            Incident inc = invocation.getArgument(0);
            appJdbcTemplate.update(
                    "UPDATE incidents SET status = ? WHERE id = ? AND tenant_id = ?",
                    inc.getStatus().name(), inc.getId(), inc.getTenantId());
            return inc;
        });

        mockBroadcaster = mock(StompBroadcasterService.class);
        TenantRepository mockTenantRepo = mock(TenantRepository.class);
        TelemetryService mockTelemetry = mock(TelemetryService.class);
        AiRcaService mockAiRca = mock(AiRcaService.class);

        incidentController = new IncidentController(
                incidentRepository,
                mockTenantRepo,
                mockTelemetry,
                mockAiRca,
                new ObjectMapper(),
                vectorRetrieverService,
                mockBroadcaster);
    }

    @BeforeEach
    void setUpTest() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL 16 pgvector Testcontainer must be available");
        testEmbeddingService.clearMappings();
        cleanDatabase();
    }

    @AfterEach
    void tearDownTest() throws Exception {
        TenantContext.clear();
        if (postgresAvailable) {
            cleanDatabase();
        }
    }

    private void cleanDatabase() throws Exception {
        TenantContext.clear();
        try (Connection conn = createMigrationConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM runbook_chunks WHERE runbook_name LIKE 'test-%' OR runbook_name LIKE 'Resolved incident%'");
            stmt.executeUpdate("DELETE FROM incidents WHERE service_name LIKE 'test-%' OR fingerprint_hash LIKE 'test-%'");
        }
    }

    @Test
    @Order(1)
    @DisplayName("Verify PostgreSQL strictly accepts exactly 768-dimensional vectors and rejects mismatched dimensions")
    void postgresVectorColumnEnforcesExactly768Dimensions() {
        TenantContext.setTenantId(TENANT_A);

        List<Double> valid768Vector = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.90);
        assertEquals(768, valid768Vector.size());
        String valid768Literal = testEmbeddingService.toVectorLiteral(valid768Vector);

        UUID chunkId = UUID.randomUUID();
        int insertedChunk = appJdbcTemplate.update("""
                INSERT INTO runbook_chunks (id, tenant_id, runbook_name, chunk_text, embedding)
                VALUES (?, ?, 'test-chunk-768', 'Sample valid 768 chunk', ?::vector)
                """, chunkId, TENANT_A, valid768Literal);
        assertEquals(1, insertedChunk);

        Integer chunkDims = appJdbcTemplate.queryForObject(
                "SELECT vector_dims(embedding) FROM runbook_chunks WHERE id = ? AND tenant_id = ?",
                Integer.class, chunkId, TENANT_A);
        assertEquals(768, chunkDims);

        UUID incidentId = UUID.randomUUID();
        int insertedIncident = appJdbcTemplate.update("""
                INSERT INTO incidents (id, tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace, embedding)
                VALUES (?, ?, 'test-fp-768', 'test-service', 'trace', 'trace', ?::vector)
                """, incidentId, TENANT_A, valid768Literal);
        assertEquals(1, insertedIncident);

        Integer incidentDims = appJdbcTemplate.queryForObject(
                "SELECT vector_dims(embedding) FROM incidents WHERE id = ? AND tenant_id = ?",
                Integer.class, incidentId, TENANT_A);
        assertEquals(768, incidentDims);

        // Verify rejection of 1536-dimensional vector at PostgreSQL level
        List<Double> invalid1536Vector = Collections.nCopies(1536, 0.02);
        String invalid1536Literal = formatLiteralRaw(invalid1536Vector);

        DataAccessException exChunk1536 = assertThrows(DataAccessException.class, () ->
                appJdbcTemplate.update("""
                        INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, embedding)
                        VALUES (?, 'test-fail-1536', 'Should fail with 1536 dims', ?::vector)
                        """, TENANT_A, invalid1536Literal));
        assertTrue(exChunk1536.getMessage().contains("768"),
                "PostgreSQL pgvector must reject 1536 dims on vector(768) column");

        DataAccessException exIncident1536 = assertThrows(DataAccessException.class, () ->
                appJdbcTemplate.update("""
                        INSERT INTO incidents (tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace, embedding)
                        VALUES (?, 'test-fail-fp-1536', 'test-service', 'trace', 'trace', ?::vector)
                        """, TENANT_A, invalid1536Literal));
        assertTrue(exIncident1536.getMessage().contains("768"),
                "PostgreSQL pgvector must reject 1536 dims on incidents.embedding column");

        // Verify rejection of small dimensional vector (e.g. 3 dims)
        String invalid3Literal = "[0.1,0.2,0.3]";
        assertThrows(DataAccessException.class, () ->
                appJdbcTemplate.update("""
                        INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, embedding)
                        VALUES (?, 'test-fail-3', 'Should fail with 3 dims', ?::vector)
                        """, TENANT_A, invalid3Literal));

        // Verify TextEmbeddingService.toVectorLiteral dimension validation
        List<Double> vector767 = Collections.nCopies(767, 0.01);
        IllegalArgumentException ex767 = assertThrows(IllegalArgumentException.class, () ->
                TextEmbeddingService.toVectorLiteral(vector767, 768));
        assertTrue(ex767.getMessage().contains("768"));

        List<Double> vector769 = Collections.nCopies(769, 0.01);
        IllegalArgumentException ex769 = assertThrows(IllegalArgumentException.class, () ->
                TextEmbeddingService.toVectorLiteral(vector769, 768));
        assertTrue(ex769.getMessage().contains("768"));
    }

    @Test
    @Order(2)
    @DisplayName("Verify real pgvector cosine ordering, threshold filtering (>= 0.72), and tenant isolation")
    void pgvectorCosineOrderingAndThresholdRetrieval() {
        TenantContext.setTenantId(TENANT_A);

        List<Double> baseQuery = DeterministicTestEmbeddingService.baseQueryVector();
        List<Double> vecHigh = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.95);
        List<Double> vecModerate = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.80);
        List<Double> vecLow = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.40);
        List<Double> vecTenantB = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.99);

        List<Double> vecIncidentHigh = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.88);
        List<Double> vecIncidentLow = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.35);
        List<Double> vecIncidentTenantB = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.98);

        // Seed runbook chunks for Tenant A
        appJdbcTemplate.update("""
                INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, embedding)
                VALUES (?, 'test-rb-high-match', 'High similarity runbook content for DB deadlocks', ?::vector)
                """, TENANT_A, testEmbeddingService.toVectorLiteral(vecHigh));

        appJdbcTemplate.update("""
                INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, embedding)
                VALUES (?, 'test-rb-moderate-match', 'Moderate similarity runbook content for connection timeouts', ?::vector)
                """, TENANT_A, testEmbeddingService.toVectorLiteral(vecModerate));

        appJdbcTemplate.update("""
                INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, embedding)
                VALUES (?, 'test-rb-low-match', 'Low similarity runbook content for CSS stylesheet cache', ?::vector)
                """, TENANT_A, testEmbeddingService.toVectorLiteral(vecLow));

        // Seed resolved incidents for Tenant A
        appJdbcTemplate.update("""
                INSERT INTO incidents (tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace, status, embedding, last_seen)
                VALUES (?, 'test-fp-inc-high', 'test-order-service', 'traceHigh', 'Deadlock in transaction block', 'RESOLVED', ?::vector, now())
                """, TENANT_A, testEmbeddingService.toVectorLiteral(vecIncidentHigh));

        appJdbcTemplate.update("""
                INSERT INTO incidents (tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace, status, embedding, last_seen)
                VALUES (?, 'test-fp-inc-low', 'test-order-service', 'traceLow', 'OutOfMemoryError in batch worker', 'RESOLVED', ?::vector, now())
                """, TENANT_A, testEmbeddingService.toVectorLiteral(vecIncidentLow));

        // Seed runbook and resolved incident for Tenant B
        TenantContext.setTenantId(TENANT_B);
        appJdbcTemplate.update("""
                INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, embedding)
                VALUES (?, 'test-rb-tenant-b', 'Tenant B proprietary runbook with high cosine score', ?::vector)
                """, TENANT_B, testEmbeddingService.toVectorLiteral(vecTenantB));

        appJdbcTemplate.update("""
                INSERT INTO incidents (tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace, status, embedding, last_seen)
                VALUES (?, 'test-fp-tenant-b', 'test-billing-service', 'traceB', 'Tenant B deadlock error', 'RESOLVED', ?::vector, now())
                """, TENANT_B, testEmbeddingService.toVectorLiteral(vecIncidentTenantB));

        // Switch back to Tenant A and execute retrieval
        TenantContext.setTenantId(TENANT_A);
        testEmbeddingService.registerMapping("test-order-service", baseQuery);

        Incident queryIncident = new Incident();
        queryIncident.setTenantId(TENANT_A);
        queryIncident.setServiceName("test-order-service");
        queryIncident.setSanitizedStackTrace("Deadlock detected while acquiring connection");

        String retrievedContext = vectorRetrieverService.retrieveContext(queryIncident);

        // 1. Verify included runbook chunks (>= 0.72)
        assertTrue(retrievedContext.contains("test-rb-high-match"),
                "High similarity runbook (0.95) must be included");
        assertTrue(retrievedContext.contains("test-rb-moderate-match"),
                "Moderate similarity runbook (0.80) must be included");

        // 2. Verify Cosine Ordering: High similarity chunk (distance ~0.05) appears before Moderate (distance ~0.20)
        int idxHigh = retrievedContext.indexOf("test-rb-high-match");
        int idxModerate = retrievedContext.indexOf("test-rb-moderate-match");
        assertTrue(idxHigh < idxModerate,
                "Higher similarity runbook (lower cosine distance) must appear before moderate similarity runbook");

        // 3. Verify Threshold exclusion (< 0.72)
        assertFalse(retrievedContext.contains("test-rb-low-match"),
                "Below-threshold runbook (0.40) must be excluded from retrieval");
        assertFalse(retrievedContext.contains("OutOfMemoryError in batch worker"),
                "Below-threshold resolved incident (0.35) must be excluded from retrieval");

        // 4. Verify Tenant Isolation (Tenant B content excluded)
        assertFalse(retrievedContext.contains("test-rb-tenant-b"),
                "Cross-tenant runbook must NOT be retrieved for Tenant A");
        assertFalse(retrievedContext.contains("Tenant B deadlock error"),
                "Cross-tenant resolved incident must NOT be retrieved for Tenant A");

        // 5. Verify Resolved Incident match (>= 0.72)
        assertTrue(retrievedContext.contains("Previously resolved incident"),
                "Matching previously resolved incident must be included");
        assertTrue(retrievedContext.contains("test-order-service"),
                "Matching service name must be in context");
        assertTrue(retrievedContext.contains("Deadlock in transaction block"),
                "Matching incident stack trace must be in context");
    }

    @Test
    @Order(3)
    @DisplayName("Verify ensureRunbooksIndexed performs lazy embedding computation for unembedded chunks")
    void lazyRunbookEmbeddingIndexesUnembeddedChunks() {
        TenantContext.setTenantId(TENANT_A);

        UUID chunkId = UUID.randomUUID();
        String chunkContent = "Lazy runbook indexing test for unclosed database sessions";
        List<Double> expectedVector = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.85);
        testEmbeddingService.registerMapping(chunkContent, expectedVector);

        appJdbcTemplate.update("""
                INSERT INTO runbook_chunks (id, tenant_id, runbook_name, chunk_text, embedding)
                VALUES (?, ?, 'test-rb-lazy', ?, NULL)
                """, chunkId, TENANT_A, chunkContent);

        // Verify initial state: embedding IS NULL
        Boolean isNullBefore = appJdbcTemplate.queryForObject(
                "SELECT (embedding IS NULL) FROM runbook_chunks WHERE id = ? AND tenant_id = ?",
                Boolean.class, chunkId, TENANT_A);
        assertTrue(isNullBefore);

        // Execute lazy indexing
        vectorRetrieverService.ensureRunbooksIndexed(TENANT_A);

        // Verify post-indexing state: embedding IS NOT NULL and dimension is 768
        Boolean isNullAfter = appJdbcTemplate.queryForObject(
                "SELECT (embedding IS NULL) FROM runbook_chunks WHERE id = ? AND tenant_id = ?",
                Boolean.class, chunkId, TENANT_A);
        assertFalse(isNullAfter);

        Integer dims = appJdbcTemplate.queryForObject(
                "SELECT vector_dims(embedding) FROM runbook_chunks WHERE id = ? AND tenant_id = ?",
                Integer.class, chunkId, TENANT_A);
        assertEquals(768, dims);
    }

    @Test
    @Order(4)
    @DisplayName("Verify human-verified resolution feedback loop via IncidentController stores only verified data and enables retrieval")
    void verifiedResolutionFeedbackLoopViaIncidentController() {
        TenantContext.setTenantId(TENANT_A);

        UUID incidentId = UUID.randomUUID();
        String serviceName = "test-payment-service";
        String failureSignature = "ConnectionPoolExhaustedException: 30000ms timeout waiting for connection";
        String rawTrace = failureSignature + "\n\tat com.airca.payment.Pool.borrow(Pool.java:99)";
        String aiHallucinationJson = """
                {
                    "rootCauseSummary": "LLM hallucinated network switch failure",
                    "suggestedRemediation": "Restart network switches on floor 2",
                    "confidence": 0.28
                }
                """;

        appJdbcTemplate.update("""
                INSERT INTO incidents (id, tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace, status, ai_rca_json, first_seen, last_seen)
                VALUES (?, ?, 'test-fp-loop', ?, ?, ?, 'OPEN', ?::jsonb, now(), now())
                """, incidentId, TENANT_A, serviceName, rawTrace, failureSignature, aiHallucinationJson);

        ResolveIncidentRequest resolveRequest = new ResolveIncidentRequest(
                "Exhaustion caused by unclosed PreparedStatement in batch refund routine",
                "gh pr merge 312 --squash && kubectl set env deployment/payment POOL_SIZE=50",
                "sre-marina@alphafin.com");

        List<Double> verifiedVector = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.92);
        testEmbeddingService.registerMapping("sre-marina@alphafin.com", verifiedVector);
        testEmbeddingService.registerMapping(serviceName, DeterministicTestEmbeddingService.baseQueryVector());

        // Resolve incident via IncidentController
        IncidentDto resolvedDto = incidentController.resolveIncident(incidentId, resolveRequest);

        assertNotNull(resolvedDto);
        assertEquals(IncidentStatus.RESOLVED, resolvedDto.status());
        assertEquals(incidentId, resolvedDto.id());

        // 1. Verify incident status transitioned in database
        String dbStatus = appJdbcTemplate.queryForObject(
                "SELECT status FROM incidents WHERE id = ? AND tenant_id = ?",
                String.class, incidentId, TENANT_A);
        assertEquals("RESOLVED", dbStatus);

        // 2. Verify indexed runbook chunk created in runbook_chunks
        Map<String, Object> chunkRow = appJdbcTemplate.queryForMap("""
                SELECT source, source_incident_id, chunk_text, vector_dims(embedding) as dims
                FROM runbook_chunks
                WHERE tenant_id = ? AND source_incident_id = ?
                """, TENANT_A, incidentId);

        assertEquals("HUMAN_VERIFIED_INCIDENT", chunkRow.get("source"));
        assertEquals(incidentId, chunkRow.get("source_incident_id"));
        assertEquals(768, ((Number) chunkRow.get("dims")).intValue());

        String indexedText = (String) chunkRow.get("chunk_text");
        assertNotNull(indexedText);

        // 3. Verify chunk contains human-verified fields + failure signature
        assertTrue(indexedText.contains("Exhaustion caused by unclosed PreparedStatement in batch refund routine"));
        assertTrue(indexedText.contains("gh pr merge 312 --squash && kubectl set env deployment/payment POOL_SIZE=50"));
        assertTrue(indexedText.contains("sre-marina@alphafin.com"));
        assertTrue(indexedText.contains(failureSignature));

        // 4. Verify chunk STRICTLY DOES NOT contain AI RCA JSON / LLM suggestions
        assertFalse(indexedText.contains("LLM hallucinated network switch failure"),
                "Indexed verified chunk must never contain AI-generated root cause");
        assertFalse(indexedText.contains("Restart network switches on floor 2"),
                "Indexed verified chunk must never contain AI-generated remediation");
        assertFalse(indexedText.contains("confidence"),
                "Indexed verified chunk must not contain AI metadata");

        // 5. Verify the newly indexed human-verified chunk is immediately retrievable for matching incidents
        Incident subsequentIncident = new Incident();
        subsequentIncident.setTenantId(TENANT_A);
        subsequentIncident.setServiceName(serviceName);
        subsequentIncident.setSanitizedStackTrace(failureSignature);

        String context = vectorRetrieverService.retrieveContext(subsequentIncident);
        assertTrue(context.contains("Vector-matched runbook"),
                "Human-verified resolution chunk must be returned by vector retriever");
        assertTrue(context.contains("sre-marina@alphafin.com"),
                "Resolver info must be surfaced in context");
        assertTrue(context.contains("Exhaustion caused by unclosed PreparedStatement"),
                "Verified fix/root cause must be surfaced in context");
    }

    @Test
    @Order(5)
    @DisplayName("Verify Task 12 ON DELETE SET NULL retention semantics: knowledge chunk survives incident deletion")
    void preserveTask12OnDeleteSetNullRetentionSemantics() {
        TenantContext.setTenantId(TENANT_A);

        UUID incidentId = UUID.randomUUID();
        String serviceName = "test-retention-service";
        String failureSig = "DeadlockException: table lock contention on settlements";

        appJdbcTemplate.update("""
                INSERT INTO incidents (id, tenant_id, fingerprint_hash, service_name, raw_stack_trace, sanitized_stack_trace, status)
                VALUES (?, ?, 'test-fp-retention', ?, ?, ?, 'OPEN')
                """, incidentId, TENANT_A, serviceName, failureSig, failureSig);

        ResolveIncidentRequest resolveRequest = new ResolveIncidentRequest(
                "Settlements batch process lacked ordered lock acquisition",
                "PR #404 merged",
                "alex@alphafin.com");

        List<Double> verifiedVector = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.91);
        testEmbeddingService.registerMapping("alex@alphafin.com", verifiedVector);
        testEmbeddingService.registerMapping(serviceName, DeterministicTestEmbeddingService.baseQueryVector());

        incidentController.resolveIncident(incidentId, resolveRequest);

        // Confirm runbook chunk is attached to incident
        UUID linkedIncident = appJdbcTemplate.queryForObject("""
                SELECT source_incident_id FROM runbook_chunks
                WHERE tenant_id = ? AND source_incident_id = ?
                """, UUID.class, TENANT_A, incidentId);
        assertEquals(incidentId, linkedIncident);

        // Delete incident (simulating retention policy purge)
        int deletedIncidents = appJdbcTemplate.update(
                "DELETE FROM incidents WHERE id = ? AND tenant_id = ?", incidentId, TENANT_A);
        assertEquals(1, deletedIncidents);

        // Verify incident row is gone
        Integer incidentCount = appJdbcTemplate.queryForObject(
                "SELECT count(*) FROM incidents WHERE id = ? AND tenant_id = ?",
                Integer.class, incidentId, TENANT_A);
        assertEquals(0, incidentCount);

        // Verify runbook chunk SURVIVED and source_incident_id is now NULL (Task 12 ON DELETE SET NULL)
        Map<String, Object> preservedChunk = appJdbcTemplate.queryForMap("""
                SELECT id, source_incident_id, source, chunk_text, vector_dims(embedding) as dims
                FROM runbook_chunks
                WHERE tenant_id = ? AND chunk_text LIKE '%alex@alphafin.com%'
                """, TENANT_A);

        assertNotNull(preservedChunk);
        assertNull(preservedChunk.get("source_incident_id"),
                "source_incident_id must be set to NULL via foreign key ON DELETE SET NULL");
        assertEquals("HUMAN_VERIFIED_INCIDENT", preservedChunk.get("source"));
        assertEquals(768, ((Number) preservedChunk.get("dims")).intValue());

        // Verify preserved chunk continues to be retrieved by semantic search
        Incident testIncident = new Incident();
        testIncident.setTenantId(TENANT_A);
        testIncident.setServiceName(serviceName);
        testIncident.setSanitizedStackTrace(failureSig);

        String context = vectorRetrieverService.retrieveContext(testIncident);
        assertTrue(context.contains("Settlements batch process lacked ordered lock acquisition"),
                "Preserved runbook chunk must remain retrievable by vector retriever even after incident deletion");
    }

    @Test
    @Order(6)
    @DisplayName("Verify tenant isolation: Tenant B cannot access or retrieve Tenant A's human-verified runbooks")
    void crossTenantIsolationEnforcedAtRlsAndVectorRetrieval() {
        // Seed verified chunk for Tenant A
        TenantContext.setTenantId(TENANT_A);
        List<Double> vectorA = DeterministicTestEmbeddingService.createVectorWithSimilarity(0.96);
        appJdbcTemplate.update("""
                INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, source, embedding)
                VALUES (?, 'test-tenant-a-confidential', 'Confidential AlphaFin runbook resolution', 'HUMAN_VERIFIED_INCIDENT', ?::vector)
                """, TENANT_A, testEmbeddingService.toVectorLiteral(vectorA));

        // Switch to Tenant B
        TenantContext.setTenantId(TENANT_B);
        testEmbeddingService.registerMapping("test-beta-service", DeterministicTestEmbeddingService.baseQueryVector());

        Incident betaIncident = new Incident();
        betaIncident.setTenantId(TENANT_B);
        betaIncident.setServiceName("test-beta-service");
        betaIncident.setSanitizedStackTrace("Common failure pattern");

        String contextB = vectorRetrieverService.retrieveContext(betaIncident);
        assertFalse(contextB.contains("Confidential AlphaFin runbook resolution"),
                "Tenant B must not retrieve Tenant A's human-verified runbook chunks");

        // Verify RLS row-level security blocks Tenant B from querying Tenant A's chunk directly
        Integer countAcrossTenant = appJdbcTemplate.queryForObject(
                "SELECT count(*) FROM runbook_chunks WHERE runbook_name = 'test-tenant-a-confidential'",
                Integer.class);
        assertEquals(0, countAcrossTenant,
                "RLS must completely filter out Tenant A's rows when app.current_tenant is Tenant B");
    }

    private static String formatLiteralRaw(List<Double> vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(vector.get(i));
        }
        return sb.append(']').toString();
    }

    /**
     * Delegating DataSource that sets PostgreSQL's app.current_tenant session variable
     * to match TenantContext for the runtime airca_app role.
     */
    private static class TenantAwareDataSource extends DelegatingDataSource {
        public TenantAwareDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = super.getConnection();
            applyTenant(conn);
            return conn;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection conn = super.getConnection(username, password);
            applyTenant(conn);
            return conn;
        }

        private void applyTenant(Connection conn) throws SQLException {
            String tenant = TenantContext.getTenantId()
                    .map(UUID::toString)
                    .orElse("");
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.current_tenant', ?, false)")) {
                ps.setString(1, tenant);
                ps.execute();
            }
        }
    }

    /**
     * Deterministic in-memory test embedding service that generates controlled 768-dimensional
     * unit vectors with exact mathematical cosine similarities.
     */
    public static class DeterministicTestEmbeddingService extends TextEmbeddingService {
        private final Map<String, List<Double>> mappings = new ConcurrentHashMap<>();

        public DeterministicTestEmbeddingService() {
            super(null, "gemini-embedding-2", 768, "http://localhost");
        }

        public void registerMapping(String key, List<Double> vector) {
            mappings.put(key, vector);
        }

        public void clearMappings() {
            mappings.clear();
        }

        @Override
        public List<Double> embed(String text) {
            if (text != null) {
                for (Map.Entry<String, List<Double>> entry : mappings.entrySet()) {
                    if (text.contains(entry.getKey())) {
                        return entry.getValue();
                    }
                }
            }
            return generateDeterministicUnitVector(text == null ? "" : text);
        }

        public static List<Double> generateDeterministicUnitVector(String text) {
            Random rng = new Random((long) text.hashCode());
            double[] raw = new double[768];
            double sumSq = 0.0;
            for (int i = 0; i < 768; i++) {
                raw[i] = rng.nextGaussian();
                sumSq += raw[i] * raw[i];
            }
            double norm = Math.sqrt(sumSq);
            List<Double> vector = new ArrayList<>(768);
            for (int i = 0; i < 768; i++) {
                vector.add(raw[i] / norm);
            }
            return vector;
        }

        public static List<Double> createVectorWithSimilarity(double targetSimilarity) {
            double clamped = Math.max(-1.0, Math.min(1.0, targetSimilarity));
            double sin = Math.sqrt(1.0 - clamped * clamped);
            List<Double> vector = new ArrayList<>(768);
            vector.add(clamped);
            vector.add(sin);
            for (int i = 2; i < 768; i++) {
                vector.add(0.0);
            }
            return vector;
        }

        public static List<Double> baseQueryVector() {
            return createVectorWithSimilarity(1.0);
        }
    }
}
