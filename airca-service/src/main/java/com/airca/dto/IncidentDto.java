package com.airca.dto;

import com.airca.model.IncidentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

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
        Instant lastSeen) {
}
