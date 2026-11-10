package com.airca.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.airca.dto.AiRcaReport;
import com.airca.model.Incident;
import com.airca.rag.RetrievalResult;
import com.airca.rag.VectorRetrieverService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiRcaServiceTest {

    private static final String API_KEY = "test-gemini-key";
    private static final String MODEL = "gemma-4-26b-a4b-it";
    private static final String EXPECTED_PATH = "/v1beta/models/" + MODEL + ":generateContent";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VectorRetrieverService retriever = mock(VectorRetrieverService.class);
    private final List<AiRcaService> services = new ArrayList<>();
    private final AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();

    private HttpServer server;
    private String baseUrl;
    private int responseStatus;
    private String responseBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        responseStatus = 200;
        responseBody = "{}";
    }

    @AfterEach
    void tearDown() {
        for (AiRcaService service : services) {
            service.close();
        }
        server.stop(0);
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedRequest.set(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                requestBody));
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private AiRcaService newService(String apiKey, String model) {
        AiRcaService service = new AiRcaService(objectMapper, retriever, apiKey, model, baseUrl);
        services.add(service);
        return service;
    }

    private String createGeminiJsonResponse(String textContent) throws Exception {
        var root = objectMapper.createObjectNode();
        var candidate = root.putArray("candidates").addObject();
        candidate.put("finishReason", "STOP");
        var parts = candidate.putObject("content").put("role", "model").putArray("parts");
        parts.addObject().put("text", textContent);
        return objectMapper.writeValueAsString(root);
    }

    private Incident createSampleIncident() {
        Incident incident = new Incident();
        incident.setServiceName("payment-service");
        incident.setSanitizedStackTrace("java.sql.SQLException: deadlock detected in transaction");
        incident.setOccurrenceCount(4);
        return incident;
    }

    @Test
    void createsDeterministicPaymentRcaWhenApiKeyIsMissing() {
        AiRcaService service = newService("", MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        AiRcaReport report = service.analyze(incident);

        assertEquals("P0", report.severity());
        assertTrue(report.rootCauseSummary().contains("Database contention"));
        assertTrue(report.confidenceScore() >= 0 && report.confidenceScore() <= 100);
        assertNull(capturedRequest.get(), "No HTTP request should be sent when API key is missing");
    }

    @Test
    void createsDeterministicRcaWhenApiKeyIsNull() {
        AiRcaService service = newService(null, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        AiRcaReport report = service.analyze(incident);

        assertEquals("P0", report.severity());
        assertNull(capturedRequest.get(), "No HTTP request should be sent when API key is null");
    }

    @Test
    void generatesRcaViaOfficialSdkWithConfiguredModelAndApiKey() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(new RetrievalResult(
                "Runbook: Payment DB deadlock RB-PAY-01",
                List.of(new VectorRetrieverService.RunbookMatch(
                        "RB-PAY-01",
                        "Payment DB deadlock runbook",
                        "docs/rb-pay-01.md",
                        null,
                        0.1,
                        0.9))));

        String modelJson = """
                {
                  "rootCauseSummary": "Contention on order table locks during refund batching",
                  "suspectedCommit": "a9f120e - batch refunds",
                  "matchedRunbook": "RB-PAY-01",
                  "confidenceScore": 95,
                  "severity": "P0",
                  "suggestedRemediation": "Roll back batch refund commit and restart payment workers"
                }
                """;
        responseBody = createGeminiJsonResponse(modelJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals("Contention on order table locks during refund batching", report.rootCauseSummary());
        assertEquals("a9f120e - batch refunds", report.suspectedCommit());
        assertEquals("RB-PAY-01", report.matchedRunbook());
        assertEquals(95, report.confidenceScore());
        assertEquals("P0", report.severity());
        assertEquals("Roll back batch refund commit and restart payment workers", report.suggestedRemediation());

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals(EXPECTED_PATH, request.path());
        assertEquals(API_KEY, request.apiKey());

        JsonNode body = objectMapper.readTree(request.body());
        assertTrue(body.has("contents"));
        String promptText = body.path("contents").get(0).path("parts").get(0).path("text").asText();
        assertTrue(promptText.contains("Service: payment-service"));
        assertTrue(promptText.contains("deadlock detected"));
        assertTrue(promptText.contains("Runbook: Payment DB deadlock RB-PAY-01"));

        assertTrue(body.has("systemInstruction"));
        String systemInstruction = body.path("systemInstruction").path("parts").get(0).path("text").asText();
        assertTrue(systemInstruction.contains("incident root-cause analyst"));
        assertTrue(systemInstruction.contains("confidenceScore"));
        assertTrue(systemInstruction.contains("All schema fields must contain non-null values"));
        assertTrue(systemInstruction.contains("No matching commit identified"));
        assertTrue(systemInstruction.contains("No directly matching runbook found"));

        assertTrue(body.has("generationConfig"));
        assertEquals("application/json", body.path("generationConfig").path("responseMimeType").asText());
    }

    @Test
    void returnedTextExtraction_extractsJsonFromMarkdownCodeFences() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        String fencedJson = """
                ```json
                {
                  "rootCauseSummary": "Lock conflict in batch refund execution",
                  "suspectedCommit": "b123456 - refund logic update",
                  "matchedRunbook": "RB-PAY-02",
                  "confidenceScore": 88,
                  "severity": "P1",
                  "suggestedRemediation": "Scale down batch refund workers"
                }
                ```
                """;
        responseBody = createGeminiJsonResponse(fencedJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals("Lock conflict in batch refund execution", report.rootCauseSummary());
        assertEquals("P1", report.severity());
        assertEquals(88, report.confidenceScore());
    }

    @Test
    void returnedTextExtraction_extractsJsonWithSurroundingPreambleAndPostamble() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        String rawWithText = """
                Based on the provided incident evidence and stack trace, here is the RCA report:
                {
                  "rootCauseSummary": "Database lock contention under heavy concurrency",
                  "suspectedCommit": "c789012 - lock order change",
                  "matchedRunbook": "RB-PAY-03",
                  "confidenceScore": 92,
                  "severity": "P0",
                  "suggestedRemediation": "Revert lock ordering change"
                }
                Please review immediately before resuming payments.
                """;
        responseBody = createGeminiJsonResponse(rawWithText);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals("Database lock contention under heavy concurrency", report.rootCauseSummary());
        assertEquals("P0", report.severity());
    }

    @Test
    void validation_normalizesSeverityAndCapsConfidence() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        String rawJson = """
                {
                  "rootCauseSummary": "Thread contention on payment processor",
                  "suspectedCommit": "d456789 - async payment processor",
                  "matchedRunbook": "RB-PAY-04",
                  "confidenceScore": 140,
                  "severity": "critical",
                  "suggestedRemediation": "Throttle incoming transactions"
                }
                """;
        responseBody = createGeminiJsonResponse(rawJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        // "critical" is not P0-P3, so it should default to P2
        assertEquals("P2", report.severity());
        // 140 exceeds 100, should be clamped to 100
        assertEquals(100, report.confidenceScore());
    }

    @Test
    void fallback_whenModelReturnsMissingFields() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        // Missing required fields
        String invalidJson = """
                {
                  "rootCauseSummary": "Something went wrong"
                }
                """;
        responseBody = createGeminiJsonResponse(invalidJson);

        AiRcaReport report = service.analyze(incident);

        // Should fall back to deterministic payment report
        assertEquals("P0", report.severity());
        assertTrue(report.rootCauseSummary().contains("Database contention"));
    }

    @Test
    void fallback_whenModelReturnsEmptyOrNoContent() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        responseBody = "{\"candidates\":[]}";

        AiRcaReport report = service.analyze(incident);

        // Fallback to deterministic report
        assertEquals("P0", report.severity());
        assertTrue(report.rootCauseSummary().contains("Database contention"));
    }

    @Test
    void fallback_whenApiReturnsHttpError() {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        responseStatus = 500;
        responseBody = "{\"error\":{\"code\":500,\"message\":\"Internal Server Error\"}}";

        AiRcaReport report = service.analyze(incident);

        // Fallback to deterministic report
        assertEquals("P0", report.severity());
        assertTrue(report.rootCauseSummary().contains("Database contention"));
    }

    @Test
    void handlesValidJsonWithNullOptionalFields_preservesModelRcaAndNormalizesToHonestDisplayValues() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        String modelJson = """
                {
                  "rootCauseSummary": "Connection pool timeout caused by leaked database connections",
                  "suspectedCommit": null,
                  "matchedRunbook": null,
                  "confidenceScore": 87,
                  "severity": "P1",
                  "suggestedRemediation": "Increase HikariCP maximum pool size and enable leak detection"
                }
                """;
        responseBody = createGeminiJsonResponse(modelJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        // Preserves model RCA and does not trigger fallback
        assertEquals("Connection pool timeout caused by leaked database connections", report.rootCauseSummary());
        assertEquals("Increase HikariCP maximum pool size and enable leak detection", report.suggestedRemediation());
        assertEquals("P1", report.severity());
        assertEquals(87, report.confidenceScore());
        // Normalizes null optional evidence fields to honest non-null display values
        assertEquals(AiRcaService.NO_MATCHING_COMMIT, report.suspectedCommit());
        assertEquals(AiRcaService.NO_MATCHING_RUNBOOK, report.matchedRunbook());
    }

    @Test
    void handlesValidJsonWithOmittedOptionalFields_normalizesToHonestDisplayValues() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        String modelJson = """
                {
                  "rootCauseSummary": "Redis cluster failover caused connection drop during settlement",
                  "confidenceScore": 91,
                  "severity": "P0",
                  "suggestedRemediation": "Verify replica promotion and reconnect clients"
                }
                """;
        responseBody = createGeminiJsonResponse(modelJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals("Redis cluster failover caused connection drop during settlement", report.rootCauseSummary());
        assertEquals(AiRcaService.NO_MATCHING_COMMIT, report.suspectedCommit());
        assertEquals(AiRcaService.NO_MATCHING_RUNBOOK, report.matchedRunbook());
        assertEquals("P0", report.severity());
        assertEquals(91, report.confidenceScore());
        assertEquals("Verify replica promotion and reconnect clients", report.suggestedRemediation());
    }

    @Test
    void handlesLiteralNullStringOrBlankForOptionalFields_normalizesToHonestDisplayValues() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        String modelJson = """
                {
                  "rootCauseSummary": "High memory consumption during large payload parsing",
                  "suspectedCommit": "null",
                  "matchedRunbook": "   ",
                  "confidenceScore": 75,
                  "severity": "P2",
                  "suggestedRemediation": "Stream request body instead of buffering in memory"
                }
                """;
        responseBody = createGeminiJsonResponse(modelJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals("High memory consumption during large payload parsing", report.rootCauseSummary());
        assertEquals(AiRcaService.NO_MATCHING_COMMIT, report.suspectedCommit());
        assertEquals(AiRcaService.NO_MATCHING_RUNBOOK, report.matchedRunbook());
        assertEquals(75, report.confidenceScore());
        assertEquals("P2", report.severity());
    }

    @Test
    void fallback_whenModelReturnsMissingOrBlankRootCauseSummary() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        String invalidJson = """
                {
                  "rootCauseSummary": "   ",
                  "suspectedCommit": "a9f120e - batch refunds",
                  "matchedRunbook": "RB-PAY-01",
                  "confidenceScore": 85,
                  "severity": "P1",
                  "suggestedRemediation": "Restart workers"
                }
                """;
        responseBody = createGeminiJsonResponse(invalidJson);

        AiRcaReport report = service.analyze(incident);

        // Blank rootCauseSummary is invalid -> falls back to deterministic local report
        assertEquals("P0", report.severity());
        assertTrue(report.rootCauseSummary().contains("Database contention"));
    }

    @Test
    void fallback_whenModelReturnsMissingOrBlankSuggestedRemediation() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Runbook: Payment DB deadlock"));

        String invalidJson = """
                {
                  "rootCauseSummary": "Database lock contention",
                  "suspectedCommit": "a9f120e - batch refunds",
                  "matchedRunbook": "RB-PAY-01",
                  "confidenceScore": 85,
                  "severity": "P1",
                  "suggestedRemediation": ""
                }
                """;
        responseBody = createGeminiJsonResponse(invalidJson);

        AiRcaReport report = service.analyze(incident);

        // Blank suggestedRemediation is invalid -> falls back to deterministic local report
        assertEquals("P0", report.severity());
        assertTrue(report.rootCauseSummary().contains("Database contention"));
    }

    @Test
    void analyze_usesDeterministicFallbackWhenRetrievalFailsWithoutRetryingLegacyRetrieval() {
        AiRcaService service = newService("", MODEL);
        Incident incident = createSampleIncident();
        when(retriever.retrieve(incident)).thenThrow(new IllegalStateException("RAG unavailable"));

        AiRcaReport report = service.analyze(incident);

        assertEquals("P0", report.severity());
        assertTrue(report.rootCauseSummary().contains("Database contention"));
        assertEquals(AiRcaService.NO_MATCHING_RUNBOOK, report.matchedRunbook());
        verify(retriever).retrieve(incident);
        verify(retriever, never()).retrieveContext(incident);
    }

    @Test
    void serialization_aiRcaReportRoundTripAndCompatibility() throws Exception {
        AiRcaReport report = new AiRcaReport(
                "Deadlock on account table",
                AiRcaService.NO_MATCHING_COMMIT,
                AiRcaService.NO_MATCHING_RUNBOOK,
                88,
                "P1",
                "Retry with exponential backoff");

        String json = objectMapper.writeValueAsString(report);
        JsonNode tree = objectMapper.readTree(json);

        assertTrue(tree.hasNonNull("rootCauseSummary"));
        assertTrue(tree.hasNonNull("suspectedCommit"));
        assertTrue(tree.hasNonNull("matchedRunbook"));
        assertTrue(tree.hasNonNull("confidenceScore"));
        assertTrue(tree.hasNonNull("severity"));
        assertTrue(tree.hasNonNull("suggestedRemediation"));

        assertEquals("Deadlock on account table", tree.path("rootCauseSummary").asText());
        assertEquals(AiRcaService.NO_MATCHING_COMMIT, tree.path("suspectedCommit").asText());
        assertEquals(AiRcaService.NO_MATCHING_RUNBOOK, tree.path("matchedRunbook").asText());
        assertEquals(88, tree.path("confidenceScore").asInt());
        assertEquals("P1", tree.path("severity").asText());
        assertEquals("Retry with exponential backoff", tree.path("suggestedRemediation").asText());

        AiRcaReport deserialized = objectMapper.readValue(json, AiRcaReport.class);
        assertEquals(report, deserialized);
    }

    @Test
    void lifecycle_closesClientWithoutException() {
        AiRcaService service = newService(API_KEY, MODEL);
        service.close();
        // Closing again should be idempotent and not throw
        service.close();
    }

    @Test
    void analyze_preservesExactTopMatchAuthority_whenModelReturnsNullMatchedRunbook() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        String expectedRunbook = "Payment Gateway Timeout & DB Deadlock Runbook (RB-PAY-01)";

        when(retriever.retrieve(incident)).thenReturn(new RetrievalResult(
                "Runbook: " + expectedRunbook,
                List.of(new VectorRetrieverService.RunbookMatch(
                        expectedRunbook,
                        "Check pool connections and kill deadlocks",
                        "docs/rb-pay-01.md",
                        null,
                        0.12,
                        0.88))));

        String modelJson = """
                {
                  "rootCauseSummary": "Connection pool timeout caused by leaked database connections",
                  "suspectedCommit": "a9f120e - batch refunds",
                  "matchedRunbook": null,
                  "confidenceScore": 87,
                  "severity": "P1",
                  "suggestedRemediation": "Increase HikariCP maximum pool size"
                }
                """;
        responseBody = createGeminiJsonResponse(modelJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals(expectedRunbook, report.matchedRunbook(),
                "Top qualifying structured runbook match must be authoritative even when model returns null");
        assertEquals("Connection pool timeout caused by leaked database connections", report.rootCauseSummary());
        assertEquals("a9f120e - batch refunds", report.suspectedCommit());
    }

    @Test
    void analyze_preservesExactTopMatchAuthority_whenModelReturnsDifferentOrHallucinatedRunbook() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        String authoritativeRunbook = "Order Service Checkout Timeout Runbook";

        when(retriever.retrieve(incident)).thenReturn(new RetrievalResult(
                "Runbook: " + authoritativeRunbook,
                List.of(new VectorRetrieverService.RunbookMatch(
                        authoritativeRunbook,
                        "Increase checkout timeout",
                        "docs/checkout.md",
                        null,
                        0.10,
                        0.90))));

        String modelJson = """
                {
                  "rootCauseSummary": "HTTP client timeout during checkout call",
                  "suspectedCommit": "b123456 - timeout change",
                  "matchedRunbook": "Hallucinated Generic Cache Runbook",
                  "confidenceScore": 90,
                  "severity": "P1",
                  "suggestedRemediation": "Extend client read timeout"
                }
                """;
        responseBody = createGeminiJsonResponse(modelJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals(authoritativeRunbook, report.matchedRunbook(),
                "Top qualifying structured runbook match must override hallucinated or divergent model output");
    }

    @Test
    void analyze_preservesExactTopMatchAuthority_whenMultipleCandidatesRetrieved() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        String topRunbook = "Primary Top Score Runbook (RB-01)";
        String secondaryRunbook = "Secondary Lower Score Runbook (RB-02)";

        when(retriever.retrieve(incident)).thenReturn(new RetrievalResult(
                "Multiple runbooks context",
                List.of(
                        new VectorRetrieverService.RunbookMatch(
                                topRunbook, "Top chunk", "docs/rb1.md", null, 0.05, 0.95),
                        new VectorRetrieverService.RunbookMatch(
                                secondaryRunbook, "Secondary chunk", "docs/rb2.md", null, 0.20, 0.80))));

        String modelJson = """
                {
                  "rootCauseSummary": "Deadlock in worker thread",
                  "suspectedCommit": "c789012 - worker update",
                  "matchedRunbook": "Secondary Lower Score Runbook (RB-02)",
                  "confidenceScore": 85,
                  "severity": "P0",
                  "suggestedRemediation": "Restart worker node"
                }
                """;
        responseBody = createGeminiJsonResponse(modelJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals(topRunbook, report.matchedRunbook(),
                "First candidate ordered by cosine distance must be the authoritative runbook value");
    }

    @Test
    void analyze_preservesNoMatchingRunbook_whenNoQualifyingMatchRetrieved_andModelReturnsNull() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();

        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Context with no runbook matches"));

        String modelJson = """
                {
                  "rootCauseSummary": "Transient socket timeout",
                  "suspectedCommit": null,
                  "matchedRunbook": null,
                  "confidenceScore": 70,
                  "severity": "P2",
                  "suggestedRemediation": "Verify external gateway reachability"
                }
                """;
        responseBody = createGeminiJsonResponse(modelJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals(AiRcaService.NO_MATCHING_RUNBOOK, report.matchedRunbook(),
                "When no qualifying candidate exists, matchedRunbook must be NO_MATCHING_RUNBOOK");
    }

    @Test
    void analyze_preservesNoMatchingRunbook_whenNoQualifyingMatchRetrieved_andModelReturnsHallucinatedRunbook() throws Exception {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();

        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("Context with no runbook matches"));

        String modelJson = """
                {
                  "rootCauseSummary": "Transient socket timeout",
                  "suspectedCommit": "d987654 - gateway call",
                  "matchedRunbook": "Completely Invented Runbook",
                  "confidenceScore": 72,
                  "severity": "P2",
                  "suggestedRemediation": "Verify external gateway reachability"
                }
                """;
        responseBody = createGeminiJsonResponse(modelJson);

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals(AiRcaService.NO_MATCHING_RUNBOOK, report.matchedRunbook(),
                "Model-hallucinated runbook must be rejected in favor of NO_MATCHING_RUNBOOK when no candidate qualified");
    }

    @Test
    void analyze_deterministicFallback_attachesTopQualifyingRunbook_whenApiKeyMissing() {
        AiRcaService service = newService("", MODEL);
        Incident incident = createSampleIncident();
        String expectedRunbook = "Authoritative Deterministic Runbook Match";

        when(retriever.retrieve(incident)).thenReturn(new RetrievalResult(
                "Context",
                List.of(new VectorRetrieverService.RunbookMatch(
                        expectedRunbook, "Chunk", "docs/rb.md", null, 0.15, 0.85))));

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals(expectedRunbook, report.matchedRunbook(),
                "Deterministic fallback must attach the top qualifying structured runbook match");
        assertEquals("P0", report.severity());
    }

    @Test
    void analyze_deterministicFallback_preservesNoMatchingRunbook_whenApiKeyMissingAndNoMatch() {
        AiRcaService service = newService("", MODEL);
        Incident incident = createSampleIncident();

        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("No match context"));

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals(AiRcaService.NO_MATCHING_RUNBOOK, report.matchedRunbook(),
                "Deterministic fallback without qualifying runbook must preserve NO_MATCHING_RUNBOOK");
    }

    @Test
    void analyze_deterministicFallback_attachesTopQualifyingRunbook_onApiError() {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();
        String expectedRunbook = "Error Recovery Runbook (RB-ERR-01)";

        when(retriever.retrieve(incident)).thenReturn(new RetrievalResult(
                "Context",
                List.of(new VectorRetrieverService.RunbookMatch(
                        expectedRunbook, "Chunk", "docs/err.md", null, 0.18, 0.82))));

        responseStatus = 500;
        responseBody = "{\"error\":{\"code\":500,\"message\":\"AI Server Unavailable\"}}";

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals(expectedRunbook, report.matchedRunbook(),
                "Deterministic fallback triggered by API error must attach top qualifying runbook match");
    }

    @Test
    void analyze_deterministicFallback_preservesNoMatchingRunbook_onApiErrorAndNoMatch() {
        AiRcaService service = newService(API_KEY, MODEL);
        Incident incident = createSampleIncident();

        when(retriever.retrieve(incident)).thenReturn(RetrievalResult.empty("No match context"));

        responseStatus = 500;
        responseBody = "{\"error\":{\"code\":500,\"message\":\"AI Server Unavailable\"}}";

        AiRcaReport report = service.analyze(incident);

        assertNotNull(report);
        assertEquals(AiRcaService.NO_MATCHING_RUNBOOK, report.matchedRunbook(),
                "Deterministic fallback triggered by API error must preserve NO_MATCHING_RUNBOOK when no candidate qualified");
    }

    private record CapturedRequest(String method, String path, String apiKey, String body) {}
}
