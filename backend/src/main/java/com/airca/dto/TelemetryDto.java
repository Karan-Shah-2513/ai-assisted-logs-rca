package com.airca.dto;

import java.util.UUID;

public record TelemetryDto(
        UUID tenantId,
        long totalLogs,
        long deduplicatedLogs,
        long uniqueIncidents,
        double deduplicationRatio,
        double estimatedCostSavedUsd) {
}
