package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiRcaReport(
        String rootCauseSummary,
        String suspectedCommit,
        String matchedRunbook,
        int confidenceScore,
        String severity,
        String suggestedRemediation) {

    public AiRcaReport withMatchedRunbook(String matchedRunbook) {
        return new AiRcaReport(
                rootCauseSummary,
                suspectedCommit,
                matchedRunbook,
                confidenceScore,
                severity,
                suggestedRemediation);
    }
}
