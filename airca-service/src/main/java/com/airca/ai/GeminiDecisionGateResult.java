package com.airca.ai;

import com.airca.dto.DecisionAnswer;
import com.airca.dto.DecisionResponse;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Encapsulates calibrated decisions, evidence filtering flags, triage route, and metadata produced by
 * {@link GeminiDecisionGateService}.
 */
public record GeminiDecisionGateResult(
        boolean isNoiseGated,
        String triageRoute,
        boolean isIncludeGitDiff,
        boolean isIncludeRunbooks,
        String calibratedSeverity,
        int calibratedConfidence,
        JsonNode decisionGateJson,
        DecisionResponse decisionResponse,
        DecisionAnswer remediationSafety,
        long decisionLatencyMs) {

    public GeminiDecisionGateResult(
            boolean isNoiseGated,
            String triageRoute,
            boolean isIncludeGitDiff,
            boolean isIncludeRunbooks,
            String calibratedSeverity,
            int calibratedConfidence,
            JsonNode decisionGateJson,
            DecisionResponse decisionResponse,
            DecisionAnswer remediationSafety) {
        this(
                isNoiseGated,
                triageRoute,
                isIncludeGitDiff,
                isIncludeRunbooks,
                calibratedSeverity,
                calibratedConfidence,
                decisionGateJson,
                decisionResponse,
                remediationSafety,
                decisionGateJson != null && decisionGateJson.has("decisionLatencyMs")
                        ? decisionGateJson.get("decisionLatencyMs").asLong(0L)
                        : 0L);
    }

    public GeminiDecisionGateResult(
            boolean isNoiseGated,
            boolean isIncludeGitDiff,
            boolean isIncludeRunbooks,
            String calibratedSeverity,
            int calibratedConfidence,
            JsonNode decisionGateJson,
            DecisionResponse decisionResponse,
            DecisionAnswer remediationSafety) {
        this(
                isNoiseGated,
                isNoiseGated ? GeminiDecisionGateService.TRIAGE_SKIP_LLM_NOISE : GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA,
                isIncludeGitDiff,
                isIncludeRunbooks,
                calibratedSeverity,
                calibratedConfidence,
                decisionGateJson,
                decisionResponse,
                remediationSafety);
    }

    public boolean noiseGated() {
        return isNoiseGated;
    }

    public boolean includeGitDiff() {
        return isIncludeGitDiff;
    }

    public boolean includeRunbooks() {
        return isIncludeRunbooks;
    }

    public boolean isFastRagOnly() {
        return GeminiDecisionGateService.TRIAGE_FAST_RAG_ONLY.equalsIgnoreCase(triageRoute);
    }

    public boolean isDeepGemma4Rca() {
        return GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA.equalsIgnoreCase(triageRoute);
    }
}
