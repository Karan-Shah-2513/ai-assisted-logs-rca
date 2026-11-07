package com.airca.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TextEmbeddingServiceTest {

    @Test
    void deterministicEmbeddingIsStableAndMatchesPgVectorDimensions() {
        TextEmbeddingService service = new TextEmbeddingService(RestClient.builder(), "", "test-model");

        List<Double> first = service.embed("payment database deadlock");
        List<Double> second = service.embed("payment database deadlock");

        assertEquals(TextEmbeddingService.DIMENSIONS, first.size());
        assertEquals(first, second);
    }
}
