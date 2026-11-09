package com.airca.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TextEmbeddingServiceTest {

    private static final String API_KEY = "test-gemini-key";
    private static final String MODEL = "gemini-embedding-2";
    private static final int DIMENSIONS = 768;
    private static final String EXPECTED_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-2:embedContent";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    private String createEmbeddingJsonResponse(int count) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode values = root.putObject("embedding").putArray("values");
        for (int i = 0; i < count; i++) {
            values.add(i * 0.001);
        }
        return objectMapper.writeValueAsString(root);
    }

    @Test
    void embed_verifiesUrlHeaderModelOutputDimensionalityAndReturns768Vector() throws Exception {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

        String sampleText = "Deadlock encountered in payment service transaction";
        String responseJson = createEmbeddingJsonResponse(DIMENSIONS);

        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", API_KEY))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.outputDimensionality").value(DIMENSIONS))
                .andExpect(jsonPath("$.content.parts[0].text").value(sampleText))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        List<Double> vector = service.embed(sampleText);

        assertNotNull(vector);
        assertEquals(DIMENSIONS, vector.size());
        assertEquals(0.0, vector.get(0), 1e-6);
        assertEquals(0.001, vector.get(1), 1e-6);
        assertEquals((DIMENSIONS - 1) * 0.001, vector.get(DIMENSIONS - 1), 1e-6);

        mockServer.verify();
    }

    @Test
    void embed_failsImmediatelyWhenApiKeyIsMissing_withoutNetworkCalls() {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, "", MODEL, DIMENSIONS);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("GEMINI_API_KEY"));

        mockServer.verify();
    }

    @Test
    void embed_failsImmediatelyWhenApiKeyIsNull_withoutNetworkCalls() {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, null, MODEL, DIMENSIONS);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("GEMINI_API_KEY"));

        mockServer.verify();
    }

    @Test
    void embed_failsWhenApiReturnsHttpServerError() {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("Gemini embedding API error")
                || exception.getMessage().contains("failed"));

        mockServer.verify();
    }

    @Test
    void embed_failsWhenApiReturnsHttpBadRequest() {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest());

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("Gemini embedding API error")
                || exception.getMessage().contains("failed"));

        mockServer.verify();
    }

    @Test
    void embed_failsWhenResponseIsMalformed_missingEmbedding() {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("malformed response"));

        mockServer.verify();
    }

    @Test
    void embed_failsWhenResponseIsMalformed_missingValuesArray() {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"embedding\": {}}", MediaType.APPLICATION_JSON));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("malformed response"));

        mockServer.verify();
    }

    @Test
    void embed_failsWhenVectorHasWrongDimensions() throws Exception {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

        // API returns 1536 dimensions instead of expected 768
        String wrongDimensionResponse = createEmbeddingJsonResponse(1536);

        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(wrongDimensionResponse, MediaType.APPLICATION_JSON));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("768") && exception.getMessage().contains("1536"));

        mockServer.verify();
    }

    @Test
    void embed_failsWhenVectorContainsNonFiniteNumber() {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

        // Build 768 values with one NaN string or null
        StringBuilder jsonBuilder = new StringBuilder("{\"embedding\":{\"values\":[");
        for (int i = 0; i < 768; i++) {
            if (i > 0) {
                jsonBuilder.append(',');
            }
            if (i == 42) {
                jsonBuilder.append("\"not-a-number\"");
            } else {
                jsonBuilder.append("0.1");
            }
        }
        jsonBuilder.append("]}}");

        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(jsonBuilder.toString(), MediaType.APPLICATION_JSON));

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> service.embed("some text"));
        assertTrue(exception.getMessage().contains("non-numeric"));

        mockServer.verify();
    }

    @Test
    void embed_respectsConfigurableDimensions() throws Exception {
        int customDimensions = 512;
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, customDimensions);

        String customResponse = createEmbeddingJsonResponse(customDimensions);

        mockServer.expect(requestTo(EXPECTED_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.outputDimensionality").value(customDimensions))
                .andRespond(withSuccess(customResponse, MediaType.APPLICATION_JSON));

        List<Double> vector = service.embed("some text");
        assertEquals(customDimensions, vector.size());

        mockServer.verify();
    }

    @Test
    void toVectorLiteral_serializesValid768Vector() {
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

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
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

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
        TextEmbeddingService service =
                new TextEmbeddingService(restClientBuilder, API_KEY, MODEL, DIMENSIONS);

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
}

