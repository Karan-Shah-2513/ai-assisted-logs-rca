package com.airca.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class TextEmbeddingServiceTest {

    private static final String API_KEY = "test-gemini-key";
    private static final String MODEL = "gemini-embedding-2";
    private static final int DIMENSIONS = 768;
    private static final String EXPECTED_PATH =
            "/v1beta/models/gemini-embedding-2:batchEmbedContents";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<TextEmbeddingService> services = new ArrayList<>();
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
        for (TextEmbeddingService service : services) {
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

    private TextEmbeddingService newService(int dimensions) {
        TextEmbeddingService service = new TextEmbeddingService(API_KEY, MODEL, dimensions, baseUrl);
        services.add(service);
        return service;
    }

    private String createEmbeddingJsonResponse(int count) throws Exception {
        var root = objectMapper.createObjectNode();
        var values = root.putArray("embeddings").addObject().putArray("values");
        for (int i = 0; i < count; i++) {
            values.add(i * 0.001);
        }
        return objectMapper.writeValueAsString(root);
    }

    @Test
    void embed_usesOfficialSdkWithConfiguredModelApiKeyAnd768Dimensions() throws Exception {
        TextEmbeddingService service = newService(DIMENSIONS);
        String sampleText = "Deadlock encountered in payment service transaction";
        responseBody = createEmbeddingJsonResponse(DIMENSIONS);

        List<Double> vector = service.embed(sampleText);

        assertNotNull(vector);
        assertEquals(DIMENSIONS, vector.size());
        assertEquals(0.0, vector.get(0), 1e-6);
        assertEquals(0.001, vector.get(1), 1e-6);
        assertEquals((DIMENSIONS - 1) * 0.001, vector.get(DIMENSIONS - 1), 1e-6);

        CapturedRequest request = capturedRequest.get();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals(EXPECTED_PATH, request.path());
        assertEquals(API_KEY, request.apiKey());
        JsonNode body = objectMapper.readTree(request.body());
        JsonNode embedRequest = body.path("requests").get(0);
        assertEquals("models/" + MODEL, embedRequest.path("model").asText());
        assertEquals(DIMENSIONS, embedRequest.path("outputDimensionality").asInt());
        assertEquals(sampleText, embedRequest.path("content").path("parts").get(0).path("text").asText());
    }

    @Test
    void embed_failsImmediatelyWhenApiKeyIsMissing_withoutNetworkCalls() {
        TextEmbeddingService service = new TextEmbeddingService("", MODEL, DIMENSIONS, baseUrl);
        services.add(service);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("GEMINI_API_KEY"));
        assertNull(capturedRequest.get());
    }

    @Test
    void embed_failsImmediatelyWhenApiKeyIsNull_withoutNetworkCalls() {
        TextEmbeddingService service = new TextEmbeddingService(null, MODEL, DIMENSIONS, baseUrl);
        services.add(service);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("GEMINI_API_KEY"));
        assertNull(capturedRequest.get());
    }

    @Test
    void embed_reportsApiErrors() {
        TextEmbeddingService service = newService(DIMENSIONS);
        responseStatus = 400;
        responseBody = "{\"error\":{\"message\":\"invalid request\"}}";

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("Gemini embedding API request failed"));
        assertNotNull(capturedRequest.get());
    }

    @Test
    void embed_failsWhenResponseIsMalformed_missingEmbeddings() {
        TextEmbeddingService service = newService(DIMENSIONS);
        responseBody = "{}";

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("malformed response"));
    }

    @Test
    void embed_failsWhenResponseIsMalformed_missingValues() {
        TextEmbeddingService service = newService(DIMENSIONS);
        responseBody = "{\"embeddings\":[{}]}";

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("malformed response"));
    }

    @Test
    void embed_failsWhenVectorHasWrongDimensions() throws Exception {
        TextEmbeddingService service = newService(DIMENSIONS);
        responseBody = createEmbeddingJsonResponse(1536);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("768") && exception.getMessage().contains("1536"));
    }

    @Test
    void embed_failsWhenVectorContainsInvalidValue() throws Exception {
        TextEmbeddingService service = newService(DIMENSIONS);
        var root = objectMapper.createObjectNode();
        var values = root.putArray("embeddings").addObject().putArray("values");
        for (int i = 0; i < DIMENSIONS; i++) {
            values.add(i == 42 ? "not-a-number" : "0.1");
        }
        responseBody = objectMapper.writeValueAsString(root);

        assertThrows(IllegalStateException.class, () -> service.embed("some text"));
    }

    @Test
    void embed_respectsConfigurableDimensionsAndInputLimit() throws Exception {
        int customDimensions = 512;
        TextEmbeddingService service = newService(customDimensions);
        responseBody = createEmbeddingJsonResponse(customDimensions);
        String input = "x".repeat(24_001);

        List<Double> vector = service.embed(input);

        assertEquals(customDimensions, vector.size());
        JsonNode body = objectMapper.readTree(capturedRequest.get().body());
        JsonNode embedRequest = body.path("requests").get(0);
        assertEquals(customDimensions, embedRequest.path("outputDimensionality").asInt());
        assertEquals(24_000, embedRequest.path("content").path("parts").get(0).path("text").asText().length());
    }

    @Test
    void toVectorLiteral_serializesValid768Vector() {
        TextEmbeddingService service = newService(DIMENSIONS);

        List<Double> vector = new ArrayList<>(DIMENSIONS);
        for (int i = 0; i < DIMENSIONS; i++) {
            vector.add(i * 0.05);
        }

        String literal = service.toVectorLiteral(vector);
        assertTrue(literal.startsWith("["));
        assertTrue(literal.endsWith("]"));
        assertEquals(DIMENSIONS - 1, literal.chars().filter(ch -> ch == ',').count());
    }

    @Test
    void toVectorLiteral_rejectsInvalidDimensions() {
        TextEmbeddingService service = newService(DIMENSIONS);

        List<Double> shortVector = new ArrayList<>();
        for (int i = 0; i < 767; i++) {
            shortVector.add(0.1);
        }

        assertThrows(IllegalArgumentException.class, () -> service.toVectorLiteral(shortVector));
        assertThrows(IllegalArgumentException.class, () -> service.toVectorLiteral(null));

        List<Double> longVector = new ArrayList<>();
        for (int i = 0; i < 1536; i++) {
            longVector.add(0.1);
        }
        assertThrows(IllegalArgumentException.class, () -> service.toVectorLiteral(longVector));
    }

    @Test
    void toVectorLiteral_rejectsNonFiniteNumbers() {
        TextEmbeddingService service = newService(DIMENSIONS);

        List<Double> nanVector = new ArrayList<>(DIMENSIONS);
        for (int i = 0; i < DIMENSIONS; i++) {
            nanVector.add(i == 10 ? Double.NaN : 0.1);
        }

        assertThrows(IllegalArgumentException.class, () -> service.toVectorLiteral(nanVector));

        List<Double> infiniteVector = new ArrayList<>(DIMENSIONS);
        for (int i = 0; i < DIMENSIONS; i++) {
            infiniteVector.add(i == 20 ? Double.POSITIVE_INFINITY : 0.1);
        }

        assertThrows(IllegalArgumentException.class, () -> service.toVectorLiteral(infiniteVector));
    }

    private record CapturedRequest(String method, String path, String apiKey, String body) {}
}
