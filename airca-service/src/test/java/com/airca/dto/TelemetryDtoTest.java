package com.airca.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelemetryDtoTest {

    private ObjectMapper objectMapper;
    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Verify TelemetryDto serialization with decision counts, latency, and aliases")
    void testSerializationWithDecisionFields() throws Exception {
        TelemetryDto dto = new TelemetryDto(
                tenantId,
                5000L,
                4900L,
                100L,
                98.0,
                73.50,
                45L,    // decisionGatedLogs (noise-only)
                120L,   // totalDecisions (total auto-triaged / evaluated)
                85.5);  // averageDecisionLatencyMs

        assertEquals(45L, dto.decisionGatedLogs());
        assertEquals(120L, dto.totalDecisions());
        assertEquals(120L, dto.decisionEvaluatedIncidents());
        assertEquals(85.5, dto.averageDecisionLatencyMs());
        assertEquals(85.5, dto.decisionLatencyMs());

        String json = objectMapper.writeValueAsString(dto);
        JsonNode tree = objectMapper.readTree(json);

        // Standard existing fields
        assertEquals(tenantId.toString(), tree.get("tenantId").asText());
        assertEquals(5000L, tree.get("totalLogs").asLong());
        assertEquals(4900L, tree.get("deduplicatedLogs").asLong());
        assertEquals(100L, tree.get("uniqueIncidents").asLong());
        assertEquals(98.0, tree.get("deduplicationRatio").asDouble(), 0.001);
        assertEquals(73.50, tree.get("estimatedCostSavedUsd").asDouble(), 0.001);

        // Extended decision telemetry fields in JSON
        assertEquals(45L, tree.get("decisionGatedLogs").asLong());
        assertEquals(120L, tree.get("totalDecisions").asLong());
        assertEquals(85.5, tree.get("averageDecisionLatencyMs").asDouble(), 0.001);
    }

    @Test
    @DisplayName("Verify 6-arg constructor preserves backwards compatibility with zero defaults")
    void testLegacyConstructorCompatibility() throws Exception {
        TelemetryDto legacy = new TelemetryDto(
                tenantId,
                1000L,
                900L,
                10L,
                90.0,
                13.50);

        assertEquals(0L, legacy.decisionGatedLogs());
        assertEquals(0L, legacy.totalDecisions());
        assertEquals(0L, legacy.decisionEvaluatedIncidents());
        assertEquals(0.0, legacy.averageDecisionLatencyMs(), 0.001);
        assertEquals(0.0, legacy.decisionLatencyMs(), 0.001);

        String json = objectMapper.writeValueAsString(legacy);
        TelemetryDto deserialized = objectMapper.readValue(json, TelemetryDto.class);
        assertEquals(legacy.tenantId(), deserialized.tenantId());
        assertEquals(0L, deserialized.decisionGatedLogs());
        assertEquals(0L, deserialized.totalDecisions());
        assertEquals(0.0, deserialized.averageDecisionLatencyMs(), 0.001);
    }

    @Test
    @DisplayName("Verify deserialization supports snake_case decision_gated_logs and aliases")
    void testDeserializationWithAliases() throws Exception {
        String json = """
                {
                    "tenantId": "11111111-1111-1111-1111-111111111111",
                    "totalLogs": 200,
                    "deduplicatedLogs": 150,
                    "uniqueIncidents": 5,
                    "deduplicationRatio": 75.0,
                    "estimatedCostSavedUsd": 2.25,
                    "decision_gated_logs": 18,
                    "decisionEvaluatedIncidents": 42,
                    "decisionLatencyMs": 99.4
                }
                """;

        TelemetryDto dto = objectMapper.readValue(json, TelemetryDto.class);
        assertEquals(18L, dto.decisionGatedLogs());
        assertEquals(42L, dto.totalDecisions());
        assertEquals(42L, dto.decisionEvaluatedIncidents());
        assertEquals(99.4, dto.averageDecisionLatencyMs(), 0.001);
        assertEquals(99.4, dto.decisionLatencyMs(), 0.001);
    }
}
