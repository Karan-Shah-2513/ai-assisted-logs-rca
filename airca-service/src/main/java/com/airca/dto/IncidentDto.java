package com.airca.dto;

import com.airca.model.IncidentStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IncidentDto(
        UUID id,
        UUID tenantId,
        String fingerprintHash,
        String serviceName,
        String rawStackTrace,
        String sanitizedStackTrace,
        long occurrenceCount,
        IncidentStatus status,
        JsonNode aiRcaJson,
        Instant firstSeen,
        Instant lastSeen,
        @JsonProperty("decisionGateJson") JsonNode decisionGateJson) {

    public IncidentDto(
            UUID id,
            UUID tenantId,
            String fingerprintHash,
            String serviceName,
            String rawStackTrace,
            String sanitizedStackTrace,
            long occurrenceCount,
            IncidentStatus status,
            JsonNode aiRcaJson,
            Instant firstSeen,
            Instant lastSeen) {
        this(
                id,
                tenantId,
                fingerprintHash,
                serviceName,
                rawStackTrace,
                sanitizedStackTrace,
                occurrenceCount,
                status,
                aiRcaJson,
                firstSeen,
                lastSeen,
                null);
    }
}
