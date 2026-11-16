package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryDto(
        UUID tenantId,
        long totalLogs,
        long deduplicatedLogs,
        long uniqueIncidents,
        double deduplicationRatio,
        double estimatedCostSavedUsd,
        @JsonProperty("decisionGatedLogs")
        @JsonAlias({"decision_gated_logs", "noiseGatedLogs"})
        long decisionGatedLogs,
        @JsonProperty("totalDecisions")
        @JsonAlias({"decisionEvaluatedIncidents", "decisionEvaluatedCount"})
        long totalDecisions,
        @JsonProperty("averageDecisionLatencyMs")
        @JsonAlias({"decisionLatencyMs", "avgDecisionLatencyMs"})
        double averageDecisionLatencyMs) {

    public TelemetryDto(
            UUID tenantId,
            long totalLogs,
            long deduplicatedLogs,
            long uniqueIncidents,
            double deduplicationRatio,
            double estimatedCostSavedUsd) {
        this(
                tenantId,
                totalLogs,
                deduplicatedLogs,
                uniqueIncidents,
                deduplicationRatio,
                estimatedCostSavedUsd,
                0L,
                0L,
                0.0);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public long decisionEvaluatedIncidents() {
        return totalDecisions;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public double decisionLatencyMs() {
        return averageDecisionLatencyMs;
    }
}
