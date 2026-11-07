package com.airca.ingestion;

import java.time.Instant;
import java.util.UUID;

public record LogMessage(
        UUID tenantId,
        String serviceName,
        String stackTrace,
        String correlationId,
        Instant receivedAt) {
}
