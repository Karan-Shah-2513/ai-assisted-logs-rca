package com.airca.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class TextEmbeddingService {

    public static final int DIMENSIONS = 1536;
    private static final int MAX_INPUT_CHARS = 24_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(TextEmbeddingService.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public TextEmbeddingService(
            RestClient.Builder restClientBuilder,
            @Value("${AI_API_KEY:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${airca.ai.embedding-model:text-embedding-3-small}") String model) {
        this.restClient = restClientBuilder.baseUrl("https://api.openai.com").build();
        this.apiKey = apiKey;
        this.model = model;
    }

    public List<Double> embed(String text) {
        String input = text == null ? "" : text.substring(0, Math.min(text.length(), MAX_INPUT_CHARS));
        if (apiKey == null || apiKey.isBlank()) {
            return deterministicEmbedding(input);
        }

        try {
            JsonNode response = restClient.post()
                    .uri("/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(Map.of("model", model, "input", input))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode vector = response == null ? null : response.path("data").path(0).path("embedding");
            if (vector == null || !vector.isArray() || vector.size() != DIMENSIONS) {
                throw new IllegalStateException("Embedding API returned an invalid vector");
            }
            List<Double> result = new ArrayList<>(DIMENSIONS);
            vector.forEach(value -> result.add(value.doubleValue()));
            return result;
        } catch (RuntimeException exception) {
            LOGGER.warn("Embedding API failed; using deterministic local embeddings", exception);
            return deterministicEmbedding(input);
        }
    }

    private static List<Double> deterministicEmbedding(String text) {
        double[] vector = new double[DIMENSIONS];
        String normalized = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
        for (String token : normalized.split("[^a-z0-9_]+")) {
            if (token.isBlank()) {
                continue;
            }
            byte[] digest = sha256(token);
            int index = ((digest[0] & 0xff) << 8 | (digest[1] & 0xff)) % DIMENSIONS;
            double sign = (digest[2] & 1) == 0 ? 1.0 : -1.0;
            vector[index] += sign * (1.0 + (digest[3] & 0xff) / 255.0);
        }

        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        List<Double> result = new ArrayList<>(DIMENSIONS);
        for (double value : vector) {
            result.add(norm == 0.0 ? 0.0 : value / norm);
        }
        return result;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static String toVectorLiteral(List<Double> vector) {
        if (vector.size() != DIMENSIONS) {
            throw new IllegalArgumentException("Expected a " + DIMENSIONS + "-dimensional vector");
        }
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector.get(i));
        }
        return literal.append(']').toString();
    }
}
