package com.airca.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.airca.model.IncidentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IncidentDtoTest {

    private ObjectMapper objectMapper;
    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("Verify IncidentDto serialization includes decisionGateJson payload")
    void testSerializationWithDecisionGateJson() throws Exception {
        String decisionJson = """
                {
                    "status": "PROCEED",
                    "triageRoute": "deep_gemma4_rca",
                    "calibratedSeverity": "P0",
                    "calibratedConfidence": 95,
                    "noiseGated": false,
                    "decisionLatencyMs": 142
                }
                """;
        JsonNode decisionNode = objectMapper.readTree(decisionJson);

        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        IncidentDto dto = new IncidentDto(
                incidentId,
                tenantId,
                "hash-12345",
                "payment-service",
                "NullPointerException at line 1",
                "NullPointerException at line <line>",
                10L,
                IncidentStatus.OPEN,
                objectMapper.createObjectNode().put("rootCauseSummary", "Bug in payment"),
                now,
                now,
                decisionNode);

        String json = objectMapper.writeValueAsString(dto);
        JsonNode serializedTree = objectMapper.readTree(json);

        assertEquals(incidentId.toString(), serializedTree.get("id").asText());
        assertEquals(tenantId.toString(), serializedTree.get("tenantId").asText());
        assertEquals("payment-service", serializedTree.get("serviceName").asText());
        assertNotNull(serializedTree.get("decisionGateJson"));
        assertEquals("PROCEED", serializedTree.get("decisionGateJson").get("status").asText());
        assertEquals("deep_gemma4_rca", serializedTree.get("decisionGateJson").get("triageRoute").asText());
        assertEquals("P0", serializedTree.get("decisionGateJson").get("calibratedSeverity").asText());
        assertEquals(95, serializedTree.get("decisionGateJson").get("calibratedConfidence").asInt());
        assertEquals(142, serializedTree.get("decisionGateJson").get("decisionLatencyMs").asLong());

        IncidentDto deserialized = objectMapper.readValue(json, IncidentDto.class);
        assertEquals(dto.id(), deserialized.id());
        assertEquals(dto.tenantId(), deserialized.tenantId());
        assertEquals(dto.decisionGateJson(), deserialized.decisionGateJson());
    }

    @Test
    @DisplayName("Verify backwards-compatible constructor sets decisionGateJson to null")
    void testBackwardsCompatibleConstructor() throws Exception {
        Instant now = Instant.parse("2026-11-14T12:00:00Z");
        IncidentDto legacyDto = new IncidentDto(
                incidentId,
                tenantId,
                "hash-legacy",
                "auth-service",
                "raw trace",
                "sanitized trace",
                1L,
                IncidentStatus.OPEN,
                null,
                now,
                now);

        assertNull(legacyDto.decisionGateJson(), "Legacy 11-arg constructor must set decisionGateJson to null");

        String json = objectMapper.writeValueAsString(legacyDto);
        JsonNode tree = objectMapper.readTree(json);
        assertTrue(tree.has("decisionGateJson"));
        assertTrue(tree.get("decisionGateJson").isNull());

        IncidentDto roundTrip = objectMapper.readValue(json, IncidentDto.class);
        assertTrue(roundTrip.decisionGateJson() == null || roundTrip.decisionGateJson().isNull());
    }
}
