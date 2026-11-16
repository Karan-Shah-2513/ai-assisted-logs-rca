package com.airca.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.airca.ai.AiRcaService;
import com.airca.ai.DecisionProperties;
import com.airca.ai.GeminiDecisionClient;
import com.airca.ai.GeminiDecisionGateResult;
import com.airca.ai.GeminiDecisionGateService;
import com.airca.controller.TelemetryService;
import com.airca.dto.AiRcaReport;
import com.airca.dto.ChoiceAnswer;
import com.airca.dto.ChoiceQuestion;
import com.airca.dto.DecisionAnswer;
import com.airca.dto.DecisionQuestion;
import com.airca.dto.DecisionRequest;
import com.airca.dto.DecisionResponse;
import com.airca.dto.IncidentDto;
import com.airca.dto.NoulAnswer;
import com.airca.dto.NoulQuestion;
import com.airca.dto.ScoreAnswer;
import com.airca.dto.ScoreQuestion;
import com.airca.dto.TelemetryDto;
import com.airca.model.Incident;
import com.airca.model.IncidentStatus;
import com.airca.multitenancy.TenantApiKeyService;
import com.airca.rag.RetrievalResult;
import com.airca.rag.VectorRetrieverService;
import com.airca.websocket.StompAuthorizationException;
import com.airca.websocket.StompBroadcasterService;
import com.airca.websocket.StompTenantAuthChannelInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 36: End-to-end hermetic backend integration test for Gemini Decisions Gate.
 *
 * <p>Validates:
 * <ol>
 *   <li>Exact POST /v1beta/decisions request fields, model, question schemas, and answer parsing.</li>
 *   <li>Low-actionability noise routing that skips Gemma 4 LLM generation and persists IGNORED_NOISE.</li>
 *   <li>Commit caused bug probability below 0.40 excludes git diff from Gemma prompt context.</li>
 *   <li>Active-tenant RLS isolation on decision_gate_json column and tenant STOMP broadcast delivery.</li>
 *   <li>Idempotent evaluation on incidents that already have cached decision gate results.</li>
 *   <li>Deterministic offline fallback when Gemini Decisions API transport is unavailable.</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GeminiDecisionGateIT extends AbstractPostgresIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TENANT_A_KEY = "alpha-decision-gate-key";
    private static final String TENANT_B_KEY = "beta-decision-gate-key";

    private static final String MOCK_GEMINI_API_KEY = "mock-gemini-decision-key";
    private static final String DECISION_MODEL = "gemini-decision-1";

    private static boolean postgresAvailable = false;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Mock HTTP transport for Gemini Decisions API
    private HttpServer mockGeminiServer;
    private String mockServerUrl;
    private final AtomicReference<CapturedRequest> lastCapturedRequest = new AtomicReference<>();
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private int stubResponseStatus = 200;
    private String stubResponseBody = "{}";

    record CapturedRequest(
            String method,
            String path,
            String apiKeyHeader,
            String contentType,
            String accept,
            String body) {}

    @BeforeAll
    static void initPostgres() {
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
            throw new RuntimeException("Failed running Liquibase migrations in GeminiDecisionGateIT", ex);
        }
    }

    @BeforeEach
    void setUpServer() throws IOException {
        mockGeminiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockGeminiServer.createContext("/v1beta/decisions", this::handleDecisionsRequest);
        mockGeminiServer.start();
        mockServerUrl = "http://127.0.0.1:" + mockGeminiServer.getAddress().getPort();

        lastCapturedRequest.set(null);
        requestCounter.set(0);
        stubResponseStatus = 200;
        stubResponseBody = "{}";
    }

    @AfterEach
    void tearDownServer() throws Exception {
        if (mockGeminiServer != null) {
            mockGeminiServer.stop(0);
        }
        if (postgresAvailable) {
            try (Connection conn = createMigrationConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM incidents WHERE service_name LIKE 'it-decision-%'");
            }
        }
    }

    private void handleDecisionsRequest(HttpExchange exchange) throws IOException {
        requestCounter.incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastCapturedRequest.set(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst(GeminiDecisionClient.API_KEY_HEADER),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Accept"),
                body));

        byte[] bytes = stubResponseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(stubResponseStatus, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private GeminiDecisionClient createDecisionClient() {
        DecisionProperties props = new DecisionProperties(
                DECISION_MODEL,
                mockServerUrl,
                "v1beta",
                true,
                5,
                MOCK_GEMINI_API_KEY);
        return new GeminiDecisionClient(props, objectMapper);
    }

    private Incident createTestIncident(UUID tenantId, String serviceName, String stackTrace) {
        Incident incident = new Incident();
        incident.setId(UUID.randomUUID());
        incident.setTenantId(tenantId);
        incident.setFingerprintHash("fp-" + UUID.randomUUID());
        incident.setServiceName(serviceName);
        incident.setRawStackTrace(stackTrace);
        incident.setSanitizedStackTrace(stackTrace);
        incident.setOccurrenceCount(1);
        incident.setStatus(IncidentStatus.OPEN);
        incident.setFirstSeen(Instant.now());
        incident.setLastSeen(Instant.now());
        return incident;
    }

    private RetrievalResult createRetrievalResult(String diff, String runbookName, String runbookText) {
        List<VectorRetrieverService.RunbookMatch> matches = runbookName != null
                ? List.of(new VectorRetrieverService.RunbookMatch(runbookName, runbookText, "runbooks/" + runbookName, diff, 0.1, 0.9))
                : Collections.emptyList();
        return new RetrievalResult("Shared context with runbook and diff", matches, Collections.emptyList(), diff);
    }

    // =========================================================================
    // Behavior 1: Exact POST /v1beta/decisions request fields, model, questions
    //             and answers parsing.
    // =========================================================================
    @Test
    @Order(1)
    @DisplayName("1. Exact POST /v1beta/decisions request fields, model, questions schema and answers parsing")
    void testExactDecisionsApiPostRequestFieldsAndParsing() throws Exception {
        stubResponseBody = """
                {
                    "answers": {
                        "is_actionable": {
                            "type": "noul",
                            "noul": 0.88,
                            "confidence": 0.94
                        },
                        "triage_route": {
                            "type": "choice",
                            "choice": "deep_gemma4_rca",
                            "confidence": 0.91,
                            "probabilities": {
                                "deep_gemma4_rca": 0.91,
                                "fast_rag_only": 0.06,
                                "skip_llm_noise": 0.03
                            }
                        },
                        "commit_caused_bug": {
                            "type": "noul",
                            "noul": 0.82,
                            "confidence": 0.87
                        },
                        "runbook_applies": {
                            "type": "noul",
                            "noul": 0.79,
                            "confidence": 0.90
                        },
                        "severity_score": {
                            "type": "score",
                            "score": 2.8,
                            "confidence": 0.93,
                            "probabilities": {
                                "3": 0.82,
                                "2": 0.15,
                                "1": 0.03,
                                "0": 0.00
                            }
                        },
                        "remediation_safety": {
                            "type": "choice",
                            "choice": "requires_human_approval",
                            "confidence": 0.85,
                            "probabilities": {
                                "requires_human_approval": 0.85,
                                "safe_auto_rollback": 0.10,
                                "escalate_dba_finance": 0.05
                            }
                        }
                    },
                    "usage": {
                        "total_input_tokens": 142,
                        "total_output_tokens": 56,
                        "total_tokens": 198
                    }
                }
                """;

        GeminiDecisionClient client = createDecisionClient();
        GeminiDecisionGateService gateService = new GeminiDecisionGateService(client, null, objectMapper);

        Incident incident = createTestIncident(TENANT_A, "it-decision-order-service",
                "org.postgresql.util.PSQLException: FATAL: remaining connection slots are reserved for non-replication superuser connections");
        RetrievalResult retrieval = createRetrievalResult(
                "commit 3a4f5b\\nAuthor: dev\\n- max_connections: 50\\n+ max_connections: 10",
                "postgres-pool-exhaustion.md",
                "Restart application pool and adjust max_connections.");

        GeminiDecisionGateResult gateResult = gateService.evaluate(incident, retrieval);

        // 1. Verify HTTP Transport details
        assertEquals(1, requestCounter.get(), "Must execute exactly one HTTP call to Decisions API");
        CapturedRequest captured = lastCapturedRequest.get();
        assertNotNull(captured, "Captured HTTP request must not be null");
        assertEquals("POST", captured.method());
        assertEquals("/v1beta/decisions", captured.path());
        assertEquals(MOCK_GEMINI_API_KEY, captured.apiKeyHeader());
        assertTrue(captured.contentType().contains("application/json"), "Content-Type must be application/json");
        assertTrue(captured.accept().contains("application/json"), "Accept must be application/json");

        // 2. Verify exact request payload schema and fields
        JsonNode requestJson = objectMapper.readTree(captured.body());
        assertEquals(DECISION_MODEL, requestJson.get("model").asText(), "model field must match gemini-decision-1");
        assertNotNull(requestJson.get("input"), "input field must be present");
        assertTrue(requestJson.get("input").asText().contains("it-decision-order-service"));
        assertTrue(requestJson.get("input").asText().contains("PSQLException"));

        JsonNode questions = requestJson.get("questions");
        assertNotNull(questions, "questions object must be present");
        assertEquals(6, questions.size(), "Request must specify exactly 6 questions");

        // Validate each question definition in request (types are serialized as lowercase: noul, choice, score)
        JsonNode qActionable = questions.get(GeminiDecisionGateService.QUESTION_IS_ACTIONABLE);
        assertEquals("noul", qActionable.get("type").asText().toLowerCase());
        assertNotNull(qActionable.get("criteria"));
        assertTrue(qActionable.get("criteria").has("true_description"));

        JsonNode qTriage = questions.get(GeminiDecisionGateService.QUESTION_TRIAGE_ROUTE);
        assertEquals("choice", qTriage.get("type").asText().toLowerCase());
        JsonNode triageChoices = qTriage.get("criteria");
        assertTrue(triageChoices.has(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA));
        assertTrue(triageChoices.has(GeminiDecisionGateService.TRIAGE_FAST_RAG_ONLY));
        assertTrue(triageChoices.has(GeminiDecisionGateService.TRIAGE_SKIP_LLM_NOISE));

        JsonNode qCommit = questions.get(GeminiDecisionGateService.QUESTION_COMMIT_CAUSED_BUG);
        assertEquals("noul", qCommit.get("type").asText().toLowerCase());

        JsonNode qRunbook = questions.get(GeminiDecisionGateService.QUESTION_RUNBOOK_APPLIES);
        assertEquals("noul", qRunbook.get("type").asText().toLowerCase());

        JsonNode qSeverity = questions.get(GeminiDecisionGateService.QUESTION_SEVERITY_SCORE);
        assertEquals("score", qSeverity.get("type").asText().toLowerCase());
        JsonNode severityCriteria = qSeverity.get("criteria");
        assertTrue(severityCriteria.isArray());
        assertEquals(4, severityCriteria.size());

        JsonNode qSafety = questions.get(GeminiDecisionGateService.QUESTION_REMEDIATION_SAFETY);
        assertEquals("choice", qSafety.get("type").asText().toLowerCase());
        JsonNode safetyChoices = qSafety.get("criteria");
        assertTrue(safetyChoices.has(GeminiDecisionGateService.REMEDIATION_SAFE_AUTO_ROLLBACK));
        assertTrue(safetyChoices.has(GeminiDecisionGateService.REMEDIATION_REQUIRES_HUMAN_APPROVAL));
        assertTrue(safetyChoices.has(GeminiDecisionGateService.REMEDIATION_ESCALATE_DBA_FINANCE));

        // 3. Verify response answers parsing
        assertNotNull(gateResult, "Gate result must be populated");
        assertFalse(gateResult.isNoiseGated(), "Actionable incident must not be noise-gated");
        assertEquals(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, gateResult.triageRoute());
        assertTrue(gateResult.isIncludeGitDiff(), "commit_caused_bug noul 0.82 >= 0.40 must include git diff");
        assertTrue(gateResult.isIncludeRunbooks(), "runbook_applies noul 0.79 >= 0.40 must include runbook context");
        assertEquals("P0", gateResult.calibratedSeverity(), "Highest probability level 3 maps to P0");
        assertEquals(93, gateResult.calibratedConfidence(), "Score confidence 0.93 maps to 93%");

        DecisionResponse response = gateResult.decisionResponse();
        assertNotNull(response);
        assertEquals(198, response.usage().totalTokens());
        assertEquals(142, response.usage().totalInputTokens());
        assertEquals(56, response.usage().totalOutputTokens());

        NoulAnswer actionableAnswer = response.getNoulAnswer(GeminiDecisionGateService.QUESTION_IS_ACTIONABLE);
        assertEquals(0.88, actionableAnswer.noul());
        assertEquals(0.94, actionableAnswer.confidence());

        ChoiceAnswer triageAnswer = response.getChoiceAnswer(GeminiDecisionGateService.QUESTION_TRIAGE_ROUTE);
        assertEquals(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, triageAnswer.choice());
        assertEquals(0.91, triageAnswer.confidence());

        ChoiceAnswer safetyAnswer = (ChoiceAnswer) gateResult.remediationSafety();
        assertEquals(GeminiDecisionGateService.REMEDIATION_REQUIRES_HUMAN_APPROVAL, safetyAnswer.choice());
    }

    // =========================================================================
    // Behavior 2: Low-actionability noise route skips Gemma and persists IGNORED_NOISE
    // =========================================================================
    @Test
    @Order(2)
    @DisplayName("2. Low-actionability noise route skips Gemma 4 and persists IGNORED_NOISE")
    void testLowActionabilityNoiseRouteSkipsGemmaAndPersistsIgnoredNoise() throws Exception {
        stubResponseBody = """
                {
                    "answers": {
                        "is_actionable": {
                            "type": "noul",
                            "noul": 0.12,
                            "confidence": 0.95
                        },
                        "triage_route": {
                            "type": "choice",
                            "choice": "skip_llm_noise",
                            "confidence": 0.95,
                            "probabilities": {
                                "skip_llm_noise": 0.95,
                                "fast_rag_only": 0.03,
                                "deep_gemma4_rca": 0.02
                            }
                        },
                        "commit_caused_bug": {
                            "type": "noul",
                            "noul": 0.05,
                            "confidence": 0.98
                        },
                        "runbook_applies": {
                            "type": "noul",
                            "noul": 0.10,
                            "confidence": 0.92
                        },
                        "severity_score": {
                            "type": "score",
                            "score": 0.0,
                            "confidence": 0.88,
                            "probabilities": {
                                "0": 0.95,
                                "1": 0.05,
                                "2": 0.00,
                                "3": 0.00
                            }
                        },
                        "remediation_safety": {
                            "type": "choice",
                            "choice": "safe_auto_rollback",
                            "confidence": 0.90
                        }
                    }
                }
                """;

        GeminiDecisionClient client = createDecisionClient();
        GeminiDecisionGateService gateService = new GeminiDecisionGateService(client, null, objectMapper);

        // VectorRetrieverService mock with RAG evidence
        VectorRetrieverService mockRetriever = Mockito.mock(VectorRetrieverService.class);
        Incident incident = createTestIncident(TENANT_A, "it-decision-noise-worker",
                "org.springframework.scheduling.quartz.JobExecutionException: Expected transient poll empty");
        RetrievalResult retrieval = createRetrievalResult(
                "diff --git a/cron.yml ...", "routine-poll.md", "Routine poll runbook.");
        Mockito.when(mockRetriever.retrieve(incident)).thenReturn(retrieval);

        // AiRcaService with decisionGateService and null/mock Gemma client
        // When noise-gated, Gemma LLM generation MUST be skipped
        AiRcaService aiRcaService = new AiRcaService(
                objectMapper,
                mockRetriever,
                null, // No live Gemma API key
                AiRcaService.DEFAULT_MODEL,
                null,
                gateService);

        AiRcaReport report = aiRcaService.analyze(incident);

        // Assert that report returned is the IGNORED_NOISE skip report
        assertNotNull(report);
        assertEquals("IGNORED_NOISE", report.rootCauseSummary(), "Noise route must produce IGNORED_NOISE root cause summary");
        assertEquals("No matching commit identified", report.suspectedCommit());
        assertEquals("No directly matching runbook found", report.matchedRunbook());
        assertEquals("No remediation required (ignored noise).", report.suggestedRemediation());
        assertEquals("P3", report.severity(), "Severity must calibrate to P3 for noise");
        assertEquals(88, report.confidenceScore(), "Confidence must match calibrated confidence from score (0.88 -> 88)");

        // Assert incident in-memory state has decisionGateJson
        JsonNode gateJson = incident.getDecisionGateJson();
        assertNotNull(gateJson, "incident.decisionGateJson must be set");
        assertTrue(gateJson.get("noiseGated").asBoolean(), "noiseGated must be true");
        assertEquals("IGNORED_NOISE", gateJson.get("status").asText());
        assertEquals("skip_llm_noise", gateJson.get("triageRoute").asText());
        assertEquals("P3", gateJson.get("calibratedSeverity").asText());
        assertEquals(88, gateJson.get("calibratedConfidence").asInt());

        // Test persistence under PostgreSQL with airca_app NOBYPASSRLS role
        assumeTrue(postgresAvailable, "PostgreSQL container must be running for persistence test");

        try (Connection conn = createAppConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement setTenant = conn.prepareStatement(
                    "SELECT set_config('app.current_tenant', ?, false)")) {
                setTenant.setString(1, TENANT_A.toString());
                setTenant.execute();
            }

            try (PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO incidents (
                        id, tenant_id, fingerprint_hash, service_name, raw_stack_trace,
                        sanitized_stack_trace, occurrence_count, status, ai_rca_json,
                        decision_gate_json, first_seen, last_seen
                    ) VALUES (
                        ?, ?, ?, ?, ?,
                        ?, ?, ?, ?::jsonb,
                        ?::jsonb, ?, ?
                    )
                    """)) {
                insert.setObject(1, incident.getId());
                insert.setObject(2, incident.getTenantId());
                insert.setString(3, incident.getFingerprintHash());
                insert.setString(4, incident.getServiceName());
                insert.setString(5, incident.getRawStackTrace());
                insert.setString(6, incident.getSanitizedStackTrace());
                insert.setLong(7, incident.getOccurrenceCount());
                insert.setString(8, incident.getStatus().name());
                insert.setString(9, objectMapper.writeValueAsString(report));
                insert.setString(10, objectMapper.writeValueAsString(gateJson));
                insert.setTimestamp(11, java.sql.Timestamp.from(incident.getFirstSeen()));
                insert.setTimestamp(12, java.sql.Timestamp.from(incident.getLastSeen()));
                insert.executeUpdate();
            }
            conn.commit();

            // Query back to verify persistence of decision_gate_json with IGNORED_NOISE
            try (PreparedStatement query = conn.prepareStatement(
                    "SELECT decision_gate_json, ai_rca_json FROM incidents WHERE id = ?")) {
                query.setObject(1, incident.getId());
                try (ResultSet rs = query.executeQuery()) {
                    assertTrue(rs.next(), "Persisted incident must be found");
                    JsonNode persistedGateJson = objectMapper.readTree(rs.getString("decision_gate_json"));
                    assertNotNull(persistedGateJson);
                    assertEquals("IGNORED_NOISE", persistedGateJson.get("status").asText());
                    assertTrue(persistedGateJson.get("noiseGated").asBoolean());
                    assertEquals("skip_llm_noise", persistedGateJson.get("triageRoute").asText());

                    JsonNode persistedRcaJson = objectMapper.readTree(rs.getString("ai_rca_json"));
                    assertNotNull(persistedRcaJson);
                    assertEquals("IGNORED_NOISE", persistedRcaJson.get("rootCauseSummary").asText());
                }
            }
        }
    }

    // =========================================================================
    // Behavior 3: commit_caused_bug < .40 excludes git diff from Gemma context
    // =========================================================================
    @Test
    @Order(3)
    @DisplayName("3. commit_caused_bug < .40 excludes git diff from Gemma prompt context")
    void testCommitCausedBugBelowThresholdExcludesGitDiffFromGemmaContext() throws Exception {
        // Sub-case A: commit_caused_bug.noul = 0.25 (< 0.40 threshold) -> git diff EXCLUDED
        stubResponseBody = """
                {
                    "answers": {
                        "is_actionable": {"type": "noul", "noul": 0.90, "confidence": 0.90},
                        "triage_route": {"type": "choice", "choice": "deep_gemma4_rca"},
                        "commit_caused_bug": {"type": "noul", "noul": 0.25, "confidence": 0.85},
                        "runbook_applies": {"type": "noul", "noul": 0.80, "confidence": 0.88},
                        "severity_score": {"type": "score", "score": 2.0, "confidence": 0.80, "probabilities": {"2": 0.80}},
                        "remediation_safety": {"type": "choice", "choice": "safe_auto_rollback"}
                    }
                }
                """;

        GeminiDecisionClient client = createDecisionClient();
        GeminiDecisionGateService gateService = new GeminiDecisionGateService(client, null, objectMapper);

        String testDiff = "diff --git a/OrderController.java\\n@@ -10,2 +10,2 @@\\n- timeout = 3000\\n+ timeout = 100";
        String runbookChunk = "Check database latency and restore default connection timeout.";

        Incident incident = createTestIncident(TENANT_A, "it-decision-diff-filter", "TimeoutException: connection timed out");
        RetrievalResult retrieval = createRetrievalResult(testDiff, "timeout-troubleshooting.md", runbookChunk);

        GeminiDecisionGateResult gateResultFiltered = gateService.evaluate(incident, retrieval);

        assertFalse(gateResultFiltered.isIncludeGitDiff(),
                "When commit_caused_bug.noul < 0.40, isIncludeGitDiff must be false");
        assertTrue(gateResultFiltered.isIncludeRunbooks(),
                "When runbook_applies.noul >= 0.40, isIncludeRunbooks must be true");

        String contextWithoutDiff = AiRcaService.buildGemmaContext(
                retrieval,
                gateResultFiltered.isIncludeGitDiff(),
                gateResultFiltered.isIncludeRunbooks());

        assertFalse(contextWithoutDiff.contains("Relevant git change:"),
                "Context must NOT contain git change header when filtered");
        assertFalse(contextWithoutDiff.contains("OrderController.java"),
                "Context must NOT contain git diff content when filtered");
        assertTrue(contextWithoutDiff.contains(runbookChunk),
                "Context must still contain qualifying runbook content");

        // Sub-case B: commit_caused_bug.noul = 0.72 (>= 0.40 threshold) -> git diff INCLUDED
        stubResponseBody = """
                {
                    "answers": {
                        "is_actionable": {"type": "noul", "noul": 0.90, "confidence": 0.90},
                        "triage_route": {"type": "choice", "choice": "deep_gemma4_rca"},
                        "commit_caused_bug": {"type": "noul", "noul": 0.72, "confidence": 0.85},
                        "runbook_applies": {"type": "noul", "noul": 0.80, "confidence": 0.88},
                        "severity_score": {"type": "score", "score": 2.0, "confidence": 0.80, "probabilities": {"2": 0.80}},
                        "remediation_safety": {"type": "choice", "choice": "safe_auto_rollback"}
                    }
                }
                """;

        Incident incidentIncluded = createTestIncident(TENANT_A, "it-decision-diff-filter-2", "TimeoutException: connection timed out");
        GeminiDecisionGateResult gateResultIncluded = gateService.evaluate(incidentIncluded, retrieval);

        assertTrue(gateResultIncluded.isIncludeGitDiff(),
                "When commit_caused_bug.noul >= 0.40, isIncludeGitDiff must be true");

        String contextWithDiff = AiRcaService.buildGemmaContext(
                retrieval,
                gateResultIncluded.isIncludeGitDiff(),
                gateResultIncluded.isIncludeRunbooks());

        assertTrue(contextWithDiff.contains("Relevant git change:"),
                "Context must contain git change header when commit_caused_bug >= 0.40");
        assertTrue(contextWithDiff.contains("OrderController.java"),
                "Context must contain git diff content when commit_caused_bug >= 0.40");
        assertTrue(contextWithDiff.contains(runbookChunk),
                "Context must still contain qualifying runbook content");
    }

    // =========================================================================
    // Behavior 4: decision_gate_json persists under active-tenant RLS and
    //             tenant STOMP broadcast includes it.
    // =========================================================================
    @Test
    @Order(4)
    @DisplayName("4A. decision_gate_json persists under active-tenant PostgreSQL RLS (isolated across tenants)")
    void testDecisionGateJsonPersistsUnderActiveTenantRls() throws Exception {
        assumeTrue(postgresAvailable, "PostgreSQL container must be running for RLS test");

        UUID incidentId = UUID.randomUUID();
        String metadataJson = """
                {
                    "decisionType": "NOUL",
                    "status": "PROCEED",
                    "triageRoute": "deep_gemma4_rca",
                    "calibratedSeverity": "P1",
                    "calibratedConfidence": 85,
                    "noiseGated": false,
                    "gitDiffIncluded": true,
                    "runbookContextIncluded": true,
                    "decisionLatencyMs": 42
                }
                """;

        // Step 1: Insert under Tenant A using airca_app NOBYPASSRLS connection
        try (Connection conn = createAppConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement setTenant = conn.prepareStatement(
                    "SELECT set_config('app.current_tenant', ?, false)")) {
                setTenant.setString(1, TENANT_A.toString());
                setTenant.execute();
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO incidents (
                        id, tenant_id, fingerprint_hash, service_name, raw_stack_trace,
                        sanitized_stack_trace, occurrence_count, status, decision_gate_json,
                        first_seen, last_seen
                    ) VALUES (
                        ?, ?, ?, ?, ?,
                        ?, 1, 'OPEN', ?::jsonb,
                        now(), now()
                    )
                    """)) {
                ps.setObject(1, incidentId);
                ps.setObject(2, TENANT_A);
                ps.setString(3, "fp-" + incidentId);
                ps.setString(4, "it-decision-rls-service");
                ps.setString(5, "NullPointerException");
                ps.setString(6, "NullPointerException");
                ps.setString(7, metadataJson);
                ps.executeUpdate();
            }
            conn.commit();

            // Step 2: Query as Tenant A -> should find incident and verify decision_gate_json
            try (PreparedStatement setTenantA = conn.prepareStatement(
                    "SELECT set_config('app.current_tenant', ?, false)")) {
                setTenantA.setString(1, TENANT_A.toString());
                setTenantA.execute();
            }

            try (PreparedStatement selectA = conn.prepareStatement(
                    "SELECT decision_gate_json FROM incidents WHERE id = ?")) {
                selectA.setObject(1, incidentId);
                try (ResultSet rs = selectA.executeQuery()) {
                    assertTrue(rs.next(), "Tenant A must be able to read its own incident");
                    JsonNode gateNode = objectMapper.readTree(rs.getString("decision_gate_json"));
                    assertNotNull(gateNode);
                    assertEquals("PROCEED", gateNode.get("status").asText());
                    assertEquals("deep_gemma4_rca", gateNode.get("triageRoute").asText());
                    assertEquals("P1", gateNode.get("calibratedSeverity").asText());
                    assertEquals(85, gateNode.get("calibratedConfidence").asInt());
                    assertFalse(gateNode.get("noiseGated").asBoolean());
                    assertEquals(42, gateNode.get("decisionLatencyMs").asInt());
                }
            }

            // Step 3: Switch session to Tenant B -> RLS MUST block access completely
            try (PreparedStatement setTenantB = conn.prepareStatement(
                    "SELECT set_config('app.current_tenant', ?, false)")) {
                setTenantB.setString(1, TENANT_B.toString());
                setTenantB.execute();
            }

            try (PreparedStatement selectB = conn.prepareStatement(
                    "SELECT decision_gate_json FROM incidents WHERE id = ?")) {
                selectB.setObject(1, incidentId);
                try (ResultSet rs = selectB.executeQuery()) {
                    assertFalse(rs.next(), "Tenant B MUST NOT see Tenant A's incident due to RLS");
                }
            }

            // Attempting count as Tenant B
            try (PreparedStatement countB = conn.prepareStatement(
                    "SELECT count(*) FROM incidents WHERE id = ?")) {
                countB.setObject(1, incidentId);
                try (ResultSet rs = countB.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "Tenant B incident count must be 0");
                }
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("4B. Tenant STOMP broadcast includes decision_gate_json and enforces tenant channel isolation")
    void testTenantStompBroadcastIncludesDecisionGateJsonAndEnforcesIsolation() throws Exception {
        // 1. Verify SimpMessagingTemplate broadcast includes decision_gate_json on /topic/tenant/<tenantId>/incidents
        SimpMessagingTemplate mockMessagingTemplate = Mockito.mock(SimpMessagingTemplate.class);
        StompBroadcasterService broadcaster = new StompBroadcasterService(mockMessagingTemplate);

        UUID incidentId = UUID.randomUUID();
        JsonNode gateNode = objectMapper.readTree("""
                {
                    "status": "PROCEED",
                    "triageRoute": "deep_gemma4_rca",
                    "calibratedSeverity": "P0",
                    "calibratedConfidence": 95,
                    "noiseGated": false,
                    "decisionLatencyMs": 15
                }
                """);

        IncidentDto incidentDto = new IncidentDto(
                incidentId,
                TENANT_A,
                "fp-broadcast-test",
                "it-decision-stomp-service",
                "DeadlockException",
                "DeadlockException",
                1L,
                IncidentStatus.OPEN,
                objectMapper.readTree("{\"rootCauseSummary\": \"DB Deadlock\"}"),
                Instant.now(),
                Instant.now(),
                gateNode);

        broadcaster.broadcastIncident(incidentDto);

        ArgumentCaptor<String> destCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockMessagingTemplate).convertAndSend(destCaptor.capture(), payloadCaptor.capture());

        assertEquals("/topic/tenant/" + TENANT_A + "/incidents", destCaptor.getValue());
        assertInstanceOf(IncidentDto.class, payloadCaptor.getValue());
        IncidentDto broadcastPayload = (IncidentDto) payloadCaptor.getValue();
        assertNotNull(broadcastPayload.decisionGateJson(), "Broadcasted IncidentDto MUST include decisionGateJson");
        assertEquals("PROCEED", broadcastPayload.decisionGateJson().get("status").asText());
        assertEquals("P0", broadcastPayload.decisionGateJson().get("calibratedSeverity").asText());
        assertEquals(95, broadcastPayload.decisionGateJson().get("calibratedConfidence").asInt());
        assertFalse(broadcastPayload.decisionGateJson().get("noiseGated").asBoolean());

        // 2. Also broadcast telemetry and verify decision gate counters
        TelemetryDto telemetryDto = TelemetryService.create(
                TENANT_A,
                100L,
                80L,
                20L,
                5L,    // decisionGatedLogs
                20L,   // totalDecisions
                18.5); // averageDecisionLatencyMs

        broadcaster.broadcastTelemetry(telemetryDto);
        Mockito.verify(mockMessagingTemplate).convertAndSend(
                Mockito.eq("/topic/tenant/" + TENANT_A + "/telemetry"),
                Mockito.eq(telemetryDto));

        // 3. Verify channel interceptor ensures Tenant A cannot be snooped on by Tenant B
        TenantApiKeyService apiKeyService = new TenantApiKeyService(Map.of(
                TENANT_A, Set.of(TENANT_A_KEY),
                TENANT_B, Set.of(TENANT_B_KEY)));

        ExecutorSubscribableChannel inboundChannel = new ExecutorSubscribableChannel(new SyncTaskExecutor());
        inboundChannel.addInterceptor(new StompTenantAuthChannelInterceptor(apiKeyService));

        // Connect Tenant A and Tenant B sessions
        assertTrue(inboundChannel.send(createConnectMessage("session-a", TENANT_A_KEY)));
        assertTrue(inboundChannel.send(createConnectMessage("session-b", TENANT_B_KEY)));

        // Tenant A can subscribe to its own topic
        assertTrue(inboundChannel.send(createSubscribeMessage("session-a", "/topic/tenant/" + TENANT_A + "/incidents")));

        // Tenant B attempting to subscribe to Tenant A's incidents topic MUST be rejected
        MessageDeliveryException ex = assertThrows(MessageDeliveryException.class, () ->
                inboundChannel.send(createSubscribeMessage("session-b", "/topic/tenant/" + TENANT_A + "/incidents")));
        assertInstanceOf(StompAuthorizationException.class, ex.getCause(),
                "Cross-tenant STOMP subscription MUST throw StompAuthorizationException");
    }

    // =========================================================================
    // Behavior 5: Idempotency of Decision Gate
    // =========================================================================
    @Test
    @Order(6)
    @DisplayName("5. Decision gate evaluation is idempotent for unique incident with existing metadata")
    void testDecisionGateEvaluationIsIdempotentForUniqueIncident() throws Exception {
        GeminiDecisionClient client = createDecisionClient();
        GeminiDecisionGateService gateService = new GeminiDecisionGateService(client, null, objectMapper);

        Incident incident = createTestIncident(TENANT_A, "it-decision-idempotent", "Error: NPE");
        JsonNode cachedMetadata = objectMapper.readTree("""
                {
                    "status": "PROCEED",
                    "triageRoute": "deep_gemma4_rca",
                    "calibratedSeverity": "P1",
                    "calibratedConfidence": 80,
                    "noiseGated": false,
                    "gitDiffIncluded": false,
                    "runbookContextIncluded": true
                }
                """);
        incident.setDecisionGateJson(cachedMetadata);

        RetrievalResult retrieval = createRetrievalResult("diff", "runbook", "text");

        // Evaluate should reuse existing metadata and NOT make an HTTP call
        GeminiDecisionGateResult result = gateService.evaluate(incident, retrieval);

        assertEquals(0, requestCounter.get(), "Must NOT invoke Decisions API if decisionGateJson is already populated");
        assertNotNull(result);
        assertEquals("P1", result.calibratedSeverity());
        assertEquals(80, result.calibratedConfidence());
        assertFalse(result.isNoiseGated());
        assertFalse(result.isIncludeGitDiff());
        assertTrue(result.isIncludeRunbooks());
        assertEquals("deep_gemma4_rca", result.triageRoute());
    }

    // =========================================================================
    // Behavior 6: Deterministic offline fallback when Decisions API fails
    // =========================================================================
    @Test
    @Order(7)
    @DisplayName("6. Deterministic offline fallback when Decisions API returns 500 error")
    void testDeterministicFallbackWhenApiFailsReturnsValidGateResult() {
        stubResponseStatus = 500;
        stubResponseBody = "{\"error\": {\"code\": 500, \"message\": \"Internal Server Error\"}}";

        GeminiDecisionClient client = createDecisionClient();
        GeminiDecisionGateService gateService = new GeminiDecisionGateService(client, null, objectMapper);

        Incident incident = createTestIncident(TENANT_A, "it-decision-fallback",
                "org.postgresql.util.PSQLException: Connection refused");
        RetrievalResult retrieval = createRetrievalResult(
                "diff --git a/App.java", "db-troubleshoot.md", "Postgres troubleshooting guide");

        GeminiDecisionGateResult result = gateService.evaluate(incident, retrieval);

        assertNotNull(result, "Fallback must return non-null result on API error");
        assertNotNull(incident.getDecisionGateJson(), "Fallback must populate incident.decisionGateJson");
        assertNotNull(result.calibratedSeverity());
        assertTrue(result.calibratedConfidence() > 0);
        assertNotNull(result.triageRoute());
    }

    // Helpers for STOMP messages
    private static Message<?> createConnectMessage(String sessionId, String apiKey) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setSessionAttributes(new ConcurrentHashMap<>());
        accessor.setNativeHeader("X-API-Key", apiKey);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<?> createSubscribeMessage(String sessionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
