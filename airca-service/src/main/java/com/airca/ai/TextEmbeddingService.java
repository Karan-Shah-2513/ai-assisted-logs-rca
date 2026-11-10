package com.airca.ai;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.google.genai.types.HttpOptions;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TextEmbeddingService {

    public static final int DEFAULT_DIMENSIONS = 768;
    public static final int DIMENSIONS = 768;
    private static final int MAX_INPUT_CHARS = 24_000;
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final Client client;
    private final String model;
    private final int dimensions;

    @Autowired
    public TextEmbeddingService(
            @Value("${GEMINI_API_KEY:}") String apiKey,
            @Value("${app.ai.embedding.model:gemini-embedding-2}") String model,
            @Value("${app.ai.embedding.dimensions:768}") int dimensions) {
        this(apiKey, model, dimensions, DEFAULT_BASE_URL);
    }

    public TextEmbeddingService(
            String apiKey,
            String model,
            int dimensions,
            String baseUrl) {
        String normalizedApiKey = apiKey != null ? apiKey.trim() : null;
        this.model = model != null && !model.isBlank() ? model.trim() : "gemini-embedding-2";
        this.dimensions = dimensions > 0 ? dimensions : DEFAULT_DIMENSIONS;
        String base = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        this.client = normalizedApiKey == null || normalizedApiKey.isBlank()
                ? null
                : Client.builder()
                        .apiKey(normalizedApiKey)
                        .httpOptions(HttpOptions.builder()
                                .baseUrl(base)
                                .apiVersion("v1beta")
                                .build())
                        .build();
    }

    public List<Double> embed(String text) {
        if (this.client == null) {
            throw new IllegalStateException("GEMINI_API_KEY must be configured for embedding generation");
        }

        String input = text == null ? "" : text.substring(0, Math.min(text.length(), MAX_INPUT_CHARS));

        EmbedContentResponse response;
        try {
            EmbedContentConfig config =
                    EmbedContentConfig.builder().outputDimensionality(this.dimensions).build();
            response = this.client.models.embedContent(this.model, input, config);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Gemini embedding API request failed: " + exception.getMessage(),
                    exception);
        }

        if (response == null || response.embeddings().isEmpty()) {
            throw new IllegalStateException("Gemini embedding API returned malformed response: missing embeddings");
        }
        List<ContentEmbedding> embeddings = response.embeddings().get();
        if (embeddings.size() != 1 || embeddings.get(0).values().isEmpty()) {
            throw new IllegalStateException("Gemini embedding API returned malformed response: missing embedding values");
        }
        List<Float> values = embeddings.get(0).values().get();
        if (values.size() != this.dimensions) {
            throw new IllegalStateException(String.format(
                    "Expected %d-dimensional embedding vector, but received %d",
                    this.dimensions,
                    values.size()));
        }

        List<Double> result = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Float element = values.get(i);
            if (element == null || !Float.isFinite(element)) {
                throw new IllegalStateException(
                        "Embedding vector contains non-finite element at index " + i + ": " + element);
            }
            result.add(element.doubleValue());
        }
        return result;
    }

    @PreDestroy
    public void close() {
        if (this.client != null) {
            this.client.close();
        }
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

