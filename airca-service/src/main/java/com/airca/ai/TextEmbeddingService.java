package com.airca.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class TextEmbeddingService {

    public static final int DEFAULT_DIMENSIONS = 768;
    public static final int DIMENSIONS = 768;
    private static final int MAX_INPUT_CHARS = 24_000;

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    @Autowired
    public TextEmbeddingService(
            RestClient.Builder restClientBuilder,
            @Value("${GEMINI_API_KEY:}") String apiKey,
            @Value("${app.ai.embedding.model:gemini-embedding-2}") String model,
            @Value("${app.ai.embedding.dimensions:768}") int dimensions) {
        this(restClientBuilder, apiKey, model, dimensions, "https://generativelanguage.googleapis.com");
    }

    public TextEmbeddingService(
            RestClient.Builder restClientBuilder,
            String apiKey,
            String model,
            int dimensions,
            String baseUrl) {
        this.apiKey = apiKey != null ? apiKey.trim() : null;
        this.model = model != null && !model.isBlank() ? model.trim() : "gemini-embedding-2";
        this.dimensions = dimensions > 0 ? dimensions : DEFAULT_DIMENSIONS;
        String base = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "https://generativelanguage.googleapis.com";
        this.restClient = restClientBuilder.baseUrl(base).build();
    }

    public List<Double> embed(String text) {
        if (this.apiKey == null || this.apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY must be configured for embedding generation");
        }

        String input = text == null ? "" : text.substring(0, Math.min(text.length(), MAX_INPUT_CHARS));

        String modelPath = this.model.startsWith("models/") ? this.model : "models/" + this.model;
        String uri = "/v1beta/" + modelPath + ":embedContent";

        Map<String, Object> requestBody = Map.of(
                "model", this.model,
                "content", Map.of(
                        "parts", List.of(
                                Map.of("text", input))),
                "outputDimensionality", this.dimensions);

        JsonNode response;
        try {
            response = restClient.post()
                    .uri(uri)
                    .header("x-goog-api-key", this.apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    "Gemini embedding API error: HTTP " + exception.getStatusCode() + " - " + exception.getResponseBodyAsString(),
                    exception);
        } catch (RestClientException exception) {
            throw new IllegalStateException(
                    "Gemini embedding API call failed: " + exception.getMessage(),
                    exception);
        }

        if (response == null || !response.hasNonNull("embedding")) {
            throw new IllegalStateException("Gemini embedding API returned malformed response: missing 'embedding'");
        }
        JsonNode valuesNode = response.path("embedding").path("values");
        if (valuesNode == null || !valuesNode.isArray()) {
            throw new IllegalStateException("Gemini embedding API returned malformed response: missing 'embedding.values' array");
        }
        if (valuesNode.size() != this.dimensions) {
            throw new IllegalStateException(String.format(
                    "Expected %d-dimensional embedding vector, but received %d",
                    this.dimensions,
                    valuesNode.size()));
        }

        List<Double> result = new ArrayList<>(this.dimensions);
        for (int i = 0; i < valuesNode.size(); i++) {
            JsonNode element = valuesNode.get(i);
            if (element == null || !element.isNumber()) {
                throw new IllegalStateException(
                        "Embedding vector contains non-numeric element at index " + i + ": " + element);
            }
            double val = element.doubleValue();
            if (!Double.isFinite(val)) {
                throw new IllegalStateException(
                        "Embedding vector contains non-finite number at index " + i + ": " + val);
            }
            result.add(val);
        }
        return result;
    }

    public String toVectorLiteral(List<Double> vector) {
        return toVectorLiteral(vector, this.dimensions);
    }

    public static String toVectorLiteral(List<Double> vector, int expectedDimensions) {
        if (vector == null || vector.size() != expectedDimensions) {
            throw new IllegalArgumentException(String.format(
                    "Expected a %d-dimensional vector, but got %s",
                    expectedDimensions,
                    vector == null ? "null" : vector.size()));
        }
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            Double value = vector.get(i);
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Embedding vector contains non-finite number at index " + i + ": " + value);
            }
            if (i > 0) {
                literal.append(',');
            }
            literal.append(value);
        }
        return literal.append(']').toString();
    }

    public int getDimensions() {
        return this.dimensions;
    }

    public String getModel() {
        return this.model;
    }
}

