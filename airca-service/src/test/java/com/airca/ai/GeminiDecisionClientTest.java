package com.airca.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.airca.dto.ChoiceAnswer;
import com.airca.dto.ChoiceQuestion;
import com.airca.dto.DecisionAnswer;
import com.airca.dto.DecisionRequest;
import com.airca.dto.DecisionResponse;
import com.airca.dto.NoulAnswer;
import com.airca.dto.NoulQuestion;
import com.airca.dto.ScoreAnswer;
import com.airca.dto.ScoreQuestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiDecisionClientTest {

    private static final String VALID_API_KEY = "test-live-gemini-key";
    private static final String MODEL = "gemini-decision-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);

    private HttpServer server;
    private String serverUrl;
    private int responseStatus;
    private String responseBody;

    record CapturedRequest(String method, String path, String apiKeyHeader, String contentType, String accept, String body) {}

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        serverUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        responseStatus = 200;
        responseBody = "{}";
        capturedRequest.set(null);
        requestCount.set(0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedRequest.set(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Accept"),
                body));

        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private GeminiDecisionClient createClient(String apiKey, boolean enabled) {
        DecisionProperties props = new DecisionProperties(
                MODEL,
                serverUrl,
                "v1beta",
                enabled,
                5,
                apiKey);
        return new GeminiDecisionClient(props, objectMapper);
    }

    private DecisionRequest buildSampleRequest() {
        return DecisionRequest.builder()
                .model(MODEL)
                .input("Customer message: Production payment transactions failing with deadlock 503 errors!")
                .question("is_outage", NoulQuestion.of(
                        "Determine whether this indicates an active outage.",
                        "Active production outage",
                        "Routine inquiry or test"))
                .question("priority", ChoiceQuestion.of(
                        "Determine severity",
                        Map.of(
                                "p1_critical", "Active production outage",
                                "p2_high", "Degraded feature",
                                "p3_normal", "Minor issue")))
                .question("urgency", ScoreQuestion.of(
                        "Rate urgency from 0 to 2",
                        "Routine SLA", "Same day follow-up", "Immediate escalation"))
                .build();
    }

    @Test
    @DisplayName("Should submit exact official contract: POST /v1beta/decisions with x-goog-api-key and parse response")
    void shouldSubmitOfficialRequestAndParseResponse() throws Exception {
        responseStatus = 200;
        responseBody = """
                {
                  "answers": {
                    "is_outage": {
                      "type": "noul",
                      "noul": 0.98,
                      "confidence": 0.98
                    },
                    "priority": {
                      "type": "choice",
                      "choice": "p1_critical",
                      "confidence": 0.93,
                      "probabilities": {
                        "p1_critical": 0.93,
                        "p2_high": 0.06,
                        "p3_normal": 0.01
                      }
                    },
                    "urgency": {
                      "type": "score",
                      "score": 2.0,
                      "confidence": 0.91,
                      "probabilities": {
                        "0": 0.01,
                        "1": 0.08,
                        "2": 0.91
                      },
                      "legend": {
                        "0": "Routine SLA",
                        "1": "Same day follow-up",
                        "2": "Immediate escalation"
                      }
                    }
                  },
                  "usage": {
                    "total_input_tokens": 120,
                    "total_output_tokens": 0,
                    "total_tokens": 120,
                    "input_tokens_by_modality": [
                      {
                        "modality": "text",
                        "tokens": 120
                      }
                    ]
                  },
                  "prompt_feedback": {
                    "block_reason": null,
                    "block_reason_message": null
                  }
                }
                """;

        GeminiDecisionClient client = createClient(VALID_API_KEY, true);
        DecisionRequest request = buildSampleRequest();

        DecisionResponse response = client.decide(request);

        assertThat(requestCount.get()).isEqualTo(1);
        CapturedRequest captured = capturedRequest.get();
        assertThat(captured).isNotNull();
        assertThat(captured.method()).isEqualTo("POST");
        assertThat(captured.path()).isEqualTo("/v1beta/decisions");
        assertThat(captured.apiKeyHeader()).isEqualTo(VALID_API_KEY);
        assertThat(captured.contentType()).contains("application/json");
        assertThat(captured.accept()).contains("application/json");

        // Verify JSON payload
        JsonNode payloadJson = objectMapper.readTree(captured.body());
        assertThat(payloadJson.get("model").asText()).isEqualTo("gemini-decision-1");
        assertThat(payloadJson.get("input").asText()).contains("Production payment transactions failing");
        assertThat(payloadJson.get("questions")).isNotNull();
        assertThat(payloadJson.get("questions").has("is_outage")).isTrue();
        assertThat(payloadJson.get("questions").has("priority")).isTrue();
        assertThat(payloadJson.get("questions").has("urgency")).isTrue();

        // Verify parsed response DTO
        assertThat(response).isNotNull();
        assertThat(response.isBlocked()).isFalse();

        NoulAnswer noul = response.getNoulAnswer("is_outage");
        assertThat(noul).isNotNull();
        assertThat(noul.probability()).isEqualTo(0.98);
        assertThat(noul.confidenceScore()).isEqualTo(0.98);
        assertThat(noul.isAffirmative()).isTrue();

        ChoiceAnswer choice = response.getChoiceAnswer("priority");
        assertThat(choice).isNotNull();
        assertThat(choice.choice()).isEqualTo("p1_critical");
        assertThat(choice.probability("p1_critical")).isEqualTo(0.93);

        ScoreAnswer score = response.getScoreAnswer("urgency");
        assertThat(score).isNotNull();
        assertThat(score.scoreValue()).isEqualTo(2.0);
        assertThat(score.probabilities()).containsEntry("2", 0.91);

        assertThat(response.usage()).isNotNull();
        assertThat(response.usage().totalTokens()).isEqualTo(120);
    }

    @Test
    @DisplayName("Should use deterministic fallback without network call when API key is empty or null")
    void shouldFallbackDeterministicallyWhenApiKeyMissing() {
        GeminiDecisionClient clientWithEmptyKey = createClient("", true);
        DecisionRequest request = buildSampleRequest();

        DecisionResponse response = clientWithEmptyKey.decide(request);

        assertThat(requestCount.get()).isEqualTo(0);
        assertThat(response).isNotNull();
        assertThat(response.answers()).isNotEmpty();
        assertThat(response.getNoulAnswer("is_outage")).isNotNull();
        assertThat(response.getNoulAnswer("is_outage").isAffirmative()).isTrue();

        // Null key test
        GeminiDecisionClient clientWithNullKey = createClient(null, true);
        DecisionResponse response2 = clientWithNullKey.decide(request);
        assertThat(requestCount.get()).isEqualTo(0);
        assertThat(response2).isNotNull();
        assertThat(response2.getNoulAnswer("is_outage").isAffirmative()).isTrue();
    }

    @Test
    @DisplayName("Should use deterministic fallback without network call when dummy API key is configured")
    void shouldFallbackDeterministicallyWhenDummyApiKeyConfigured() {
        for (String dummyKey : List.of("dummy", "dummy-key", "dummy_key", "dummy-api-key", "DUMMY", "placeholder", "none")) {
            GeminiDecisionClient client = createClient(dummyKey, true);
            DecisionResponse response = client.decide(buildSampleRequest());

            assertThat(requestCount.get()).isEqualTo(0);
            assertThat(response).isNotNull();
            assertThat(response.answers()).containsKey("is_outage");
            assertThat(response.answers()).containsKey("priority");
            assertThat(response.answers()).containsKey("urgency");
        }
    }

    @Test
    @DisplayName("Should use deterministic fallback without network call when decisions are disabled")
    void shouldFallbackDeterministicallyWhenDisabled() {
        GeminiDecisionClient client = createClient(VALID_API_KEY, false);
        DecisionResponse response = client.decide(buildSampleRequest());

        assertThat(requestCount.get()).isEqualTo(0);
        assertThat(response).isNotNull();
        assertThat(response.answers()).isNotEmpty();
    }

    @Test
    @DisplayName("Should use deterministic fallback when server returns HTTP 500 error")
    void shouldFallbackDeterministicallyOnHttp500() {
        responseStatus = 500;
        responseBody = "{\"error\": {\"code\": 500, \"message\": \"Internal Server Error\"}}";

        GeminiDecisionClient client = createClient(VALID_API_KEY, true);
        DecisionResponse response = client.decide(buildSampleRequest());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(response).isNotNull();
        assertThat(response.answers()).containsKey("is_outage");
        assertThat(response.getNoulAnswer("is_outage").isAffirmative()).isTrue();
    }

    @Test
    @DisplayName("Should use deterministic fallback when server returns HTTP 401 unauthorized")
    void shouldFallbackDeterministicallyOnHttp401() {
        responseStatus = 401;
        responseBody = "{\"error\": {\"code\": 401, \"message\": \"API_KEY_INVALID\"}}";

        GeminiDecisionClient client = createClient(VALID_API_KEY, true);
        DecisionResponse response = client.decide(buildSampleRequest());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(response).isNotNull();
        assertThat(response.answers()).containsKey("priority");
    }

    @Test
    @DisplayName("Should use deterministic fallback when server returns empty or malformed body")
    void shouldFallbackDeterministicallyOnMalformedResponse() {
        responseStatus = 200;
        responseBody = "not valid json {";

        GeminiDecisionClient client = createClient(VALID_API_KEY, true);
        DecisionResponse response = client.decide(buildSampleRequest());

        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(response).isNotNull();
        assertThat(response.answers()).containsKey("is_outage");
    }

    @Test
    @DisplayName("Should use deterministic fallback when server is unreachable or connection refused")
    void shouldFallbackDeterministicallyOnConnectionFailure() {
        DecisionProperties deadServerProps = new DecisionProperties(
                MODEL,
                "http://127.0.0.1:1", // dead port
                "v1beta",
                true,
                1,
                VALID_API_KEY);
        GeminiDecisionClient client = new GeminiDecisionClient(deadServerProps, objectMapper);

        DecisionResponse response = client.decide(buildSampleRequest());

        assertThat(response).isNotNull();
        assertThat(response.answers()).isNotEmpty();
    }

    @Test
    @DisplayName("Should verify quality and calibration of deterministic fallback for various question types")
    void shouldVerifyFallbackQualityForDifferentQuestionTypes() {
        GeminiDecisionClient client = createClient("", true);

        // 1. Critical outage scenario
        DecisionRequest criticalRequest = DecisionRequest.builder()
                .input("FATAL: Database deadlock in connection pool, checkout failing with 503")
                .question("is_outage", NoulQuestion.of("Is active outage?"))
                .question("is_noise", NoulQuestion.of("Is noise?"))
                .question("priority", ChoiceQuestion.of("Select priority", Map.of(
                        "p1_critical", "Outage",
                        "p2_high", "Degraded",
                        "p3_normal", "Minor")))
                .question("urgency", ScoreQuestion.of("Urgency", "Low", "Medium", "High"))
                .build();

        DecisionResponse criticalResponse = client.decide(criticalRequest);
        NoulAnswer outageAns = criticalResponse.getNoulAnswer("is_outage");
        assertThat(outageAns.isAffirmative()).isTrue();
        assertThat(outageAns.probability()).isGreaterThanOrEqualTo(0.90);

        NoulAnswer noiseAns = criticalResponse.getNoulAnswer("is_noise");
        assertThat(noiseAns.isAffirmative()).isFalse();
        assertThat(noiseAns.probability()).isLessThan(0.20);

        ChoiceAnswer prioAns = criticalResponse.getChoiceAnswer("priority");
        assertThat(prioAns.choice()).isEqualTo("p1_critical");
        assertThat(prioAns.probability("p1_critical")).isGreaterThan(0.80);

        ScoreAnswer urgencyAns = criticalResponse.getScoreAnswer("urgency");
        assertThat(urgencyAns.scoreValue()).isEqualTo(2.0); // Highest index for 3 elements (0, 1, 2)
        assertThat(urgencyAns.probabilities().get("2")).isGreaterThan(0.80);

        // 2. Benign/routine inquiry scenario
        DecisionRequest benignRequest = DecisionRequest.builder()
                .input("Ping healthcheck ok: scheduled backup completed successfully")
                .question("is_outage", NoulQuestion.of("Is active outage?"))
                .question("is_noise", NoulQuestion.of("Is noise?"))
                .question("urgency", ScoreQuestion.of("Urgency", "Low", "Medium", "High"))
                .build();

        DecisionResponse benignResponse = client.decide(benignRequest);
        assertThat(benignResponse.getNoulAnswer("is_outage").isAffirmative()).isFalse();
        assertThat(benignResponse.getNoulAnswer("is_noise").isAffirmative()).isTrue();
        assertThat(benignResponse.getScoreAnswer("urgency").scoreValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should resolve decisions URL accurately for various endpoint formats")
    void shouldResolveDecisionsUrlAccurately() {
        GeminiDecisionClient defaultClient = new GeminiDecisionClient(new DecisionProperties());
        assertThat(defaultClient.resolveDecisionsUrl())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/decisions");

        DecisionProperties customProps = new DecisionProperties(
                "gemini-decision-1",
                "https://custom-proxy.internal:8443/",
                "v1beta",
                true,
                10,
                null);
        GeminiDecisionClient customClient = new GeminiDecisionClient(customProps);
        assertThat(customClient.resolveDecisionsUrl())
                .isEqualTo("https://custom-proxy.internal:8443/v1beta/decisions");

        DecisionProperties withVersionProps = new DecisionProperties(
                "gemini-decision-1",
                "https://custom-proxy.internal/v1beta",
                "v1beta",
                true,
                10,
                null);
        GeminiDecisionClient withVersionClient = new GeminiDecisionClient(withVersionProps);
        assertThat(withVersionClient.resolveDecisionsUrl())
                .isEqualTo("https://custom-proxy.internal/v1beta/decisions");
    }

    @Test
    @DisplayName("Should support convenience decide(Object input, Map<String, DecisionQuestion> questions)")
    void shouldSupportConvenienceDecideMethod() {
        GeminiDecisionClient client = createClient("", true);
        DecisionResponse response = client.decide(
                "Deadlock exception in payment service",
                Map.of("is_outage", NoulQuestion.of("Is active outage?")));

        assertThat(response).isNotNull();
        assertThat(response.getNoulAnswer("is_outage")).isNotNull();
        assertThat(response.getNoulAnswer("is_outage").isAffirmative()).isTrue();
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when request is null")
    void shouldThrowWhenRequestIsNull() {
        GeminiDecisionClient client = createClient("", true);
        assertThatThrownBy(() -> client.decide(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
