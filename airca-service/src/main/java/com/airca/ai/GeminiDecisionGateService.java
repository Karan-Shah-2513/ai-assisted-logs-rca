package com.airca.ai;

import com.airca.deduplication.RedisDeduplicatorService;
import com.airca.dto.ChoiceAnswer;
import com.airca.dto.ChoiceQuestion;
import com.airca.dto.DecisionAnswer;
import com.airca.dto.DecisionQuestion;
import com.airca.dto.DecisionRequest;
import com.airca.dto.DecisionResponse;
import com.airca.dto.GenericDecisionAnswer;
import com.airca.dto.NoulAnswer;
import com.airca.dto.NoulQuestion;
import com.airca.dto.ScoreAnswer;
import com.airca.dto.ScoreQuestion;
import com.airca.model.Incident;
import com.airca.rag.RetrievalResult;
import com.airca.rag.VectorRetrieverService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * System 1 calibrated decision gate service powered by Gemini Decisions API (gemini-decision-1).
 *
 * <p>Orchestrates calibrated gating prior to Gemma 4 generation:
 * <ul>
 *   <li>Noise Gate: filters non-actionable or trivial noise based on calibrated thresholds.</li>
 *   <li>Triage Route: supports deep_gemma4_rca, fast_rag_only, and skip_llm_noise.</li>
 *   <li>Evidence Filtering: omits weak git diffs and weak runbooks below probability 0.40.</li>
 *   <li>Severity Mapping: maps highest probability level (3->P0, 2->P1, 1->P2, 0->P3) and confidence.</li>
 *   <li>Remediation Safety: evaluates operational safety as a Choice question.</li>
 *   <li>Idempotence: executes decision once per unique incident and persists metadata on {@link Incident#getDecisionGateJson()}.</li>
 * </ul>
 */
@Service
public class GeminiDecisionGateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeminiDecisionGateService.class);

    // Question IDs
    public static final String QUESTION_IS_ACTIONABLE = "is_actionable";
    public static final String QUESTION_TRIAGE_ROUTE = "triage_route";
    public static final String QUESTION_COMMIT_CAUSED_BUG = "commit_caused_bug";
    public static final String QUESTION_RUNBOOK_APPLIES = "runbook_applies";
    public static final String QUESTION_SEVERITY_SCORE = "severity_score";
    public static final String QUESTION_REMEDIATION_SAFETY = "remediation_safety";

    // Status constants
    public static final String STATUS_PROCEED = "PROCEED";
    public static final String STATUS_IGNORED_NOISE = "IGNORED_NOISE";

    // Triage route choice options
    public static final String TRIAGE_DEEP_GEMMA4_RCA = "deep_gemma4_rca";
    public static final String TRIAGE_FAST_RAG_ONLY = "fast_rag_only";
    public static final String TRIAGE_SKIP_LLM_NOISE = "skip_llm_noise";

    // Remediation safety choice options
    public static final String REMEDIATION_SAFE_AUTO_ROLLBACK = "safe_auto_rollback";
    public static final String REMEDIATION_REQUIRES_HUMAN_APPROVAL = "requires_human_approval";
    public static final String REMEDIATION_ESCALATE_DBA_FINANCE = "escalate_dba_finance";

    // Calibration thresholds
    public static final double NOISE_GATE_NOUL_THRESHOLD = 0.25;
    public static final double NOISE_GATE_CONFIDENCE_THRESHOLD = 0.80;
    public static final double EVIDENCE_FILTER_THRESHOLD = 0.40;

    private final GeminiDecisionClient decisionClient;
    private final RedisDeduplicatorService deduplicatorService;
    private final ObjectMapper objectMapper;

    @Autowired
    public GeminiDecisionGateService(
            GeminiDecisionClient decisionClient,
            @Autowired(required = false) RedisDeduplicatorService deduplicatorService,
            ObjectMapper objectMapper) {
        this.decisionClient = decisionClient;
        this.deduplicatorService = deduplicatorService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public GeminiDecisionGateService(GeminiDecisionClient decisionClient) {
        this(decisionClient, null, new ObjectMapper());
    }

    /**
     * Evaluates the incident against the Gemini Decisions Gate questions using available RAG/JGit evidence.
     *
     * @param incident the incident entity being analyzed
     * @param retrieval the shared RAG retrieval result (containing runbooks, past incidents, and git diff)
     * @return calibrated {@link GeminiDecisionGateResult}
     */
    public GeminiDecisionGateResult evaluate(Incident incident, RetrievalResult retrieval) {
        if (incident == null) {
            throw new IllegalArgumentException("Incident must not be null");
        }

        // Idempotency: invoke decision once for a unique incident
        if (incident.getDecisionGateJson() != null && !incident.getDecisionGateJson().isNull()) {
            LOGGER.debug("Incident {} already has decision gate metadata; reusing cached decision", incident.getId());
            return parseExistingGateResult(incident.getDecisionGateJson());
        }

        String input = buildDecisionInput(incident, retrieval);
        Map<String, DecisionQuestion> questions = buildDefaultQuestions();
        DecisionRequest request = DecisionRequest.of(null, input, questions);

        long startNanos = System.nanoTime();
        DecisionResponse response;
        try {
            response = decisionClient.decide(request);
        } catch (Exception ex) {
            LOGGER.warn("Gemini Decisions API invocation failed ({}: {}); using deterministic fallback",
                    ex.getClass().getSimpleName(), ex.getMessage());
            response = decisionClient.deterministicFallback(request);
        }
        long latencyMs = Math.max(1L, (System.nanoTime() - startNanos) / 1_000_000L);

        NoulAnswer isActionable = response != null ? response.getNoulAnswer(QUESTION_IS_ACTIONABLE) : null;
        ChoiceAnswer triageRouteAnswer = response != null ? response.getChoiceAnswer(QUESTION_TRIAGE_ROUTE) : null;
        NoulAnswer commitCausedBug = response != null ? response.getNoulAnswer(QUESTION_COMMIT_CAUSED_BUG) : null;
        NoulAnswer runbookApplies = response != null ? response.getNoulAnswer(QUESTION_RUNBOOK_APPLIES) : null;

        // severity_score lookup with fallback
        ScoreAnswer severityScore = response != null ? response.getScoreAnswer(QUESTION_SEVERITY_SCORE) : null;
        if (severityScore == null && response != null) {
            severityScore = response.getScoreAnswer("severity");
        }

        // remediation_safety choice lookup
        DecisionAnswer remediationSafety = response != null ? response.getAnswer(QUESTION_REMEDIATION_SAFETY) : null;

        // Noise gate condition: (is_actionable.noul < .25 && confidence >= .80) OR triage_route.choice == skip_llm_noise
        boolean noiseGated = isNoiseGated(isActionable, triageRouteAnswer);

        // Resolve triage route
        String selectedTriageRoute = resolveTriageRoute(noiseGated, triageRouteAnswer);

        if (noiseGated) {
            incrementDecisionGatedLogs(incident.getTenantId());
        }
        recordDecisionExecution(incident.getTenantId(), latencyMs);

        // Evidence filters: if commit_caused_bug.noul < .40 omit git diff; if runbook_applies.noul < .40 omit weak runbook context
        boolean includeGitDiff = shouldIncludeGitDiff(commitCausedBug);
        boolean includeRunbooks = shouldIncludeRunbooks(runbookApplies);

        // Calibrated severity & confidence mapping (3->P0, 2->P1, 1->P2, 0->P3)
        String calibratedSeverity = mapSeverityFromScore(severityScore);
        int calibratedConfidence = mapConfidenceFromScore(severityScore, 70);

        // Build metadata JSON to persist on incident.decisionGateJson
        JsonNode metadataNode = buildMetadataJson(
                noiseGated,
                selectedTriageRoute,
                includeGitDiff,
                includeRunbooks,
                calibratedSeverity,
                calibratedConfidence,
                latencyMs,
                isActionable,
                triageRouteAnswer,
                commitCausedBug,
                runbookApplies,
                severityScore,
                remediationSafety,
                response);

        incident.setDecisionGateJson(metadataNode);

        return new GeminiDecisionGateResult(
                noiseGated,
                selectedTriageRoute,
                includeGitDiff,
                includeRunbooks,
                calibratedSeverity,
                calibratedConfidence,
                metadataNode,
                response,
                remediationSafety,
                latencyMs);
    }

    /**
     * Noise gate evaluation:
     * (is_actionable.noul < .25 && confidence >= .80) OR triage_route.choice == skip_llm_noise
     */
    public static boolean isNoiseGated(NoulAnswer isActionable, ChoiceAnswer triageRoute) {
        if (isActionable != null && isActionable.noul() != null && isActionable.confidence() != null) {
            if (isActionable.noul() < NOISE_GATE_NOUL_THRESHOLD
                    && isActionable.confidence() >= NOISE_GATE_CONFIDENCE_THRESHOLD) {
                return true;
            }
        }
        if (triageRoute != null && triageRoute.choice() != null) {
            if (TRIAGE_SKIP_LLM_NOISE.equalsIgnoreCase(triageRoute.choice().trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the effective triage route based on noise-gating and triage_route choice answer.
     */
    public static String resolveTriageRoute(boolean noiseGated, ChoiceAnswer triageRouteAnswer) {
        if (noiseGated) {
            return TRIAGE_SKIP_LLM_NOISE;
        }
        if (triageRouteAnswer != null && triageRouteAnswer.choice() != null) {
            String choice = triageRouteAnswer.choice().trim().toLowerCase();
            if (TRIAGE_FAST_RAG_ONLY.equals(choice)) {
                return TRIAGE_FAST_RAG_ONLY;
            }
            if (TRIAGE_SKIP_LLM_NOISE.equals(choice)) {
                return TRIAGE_SKIP_LLM_NOISE;
            }
            if (TRIAGE_DEEP_GEMMA4_RCA.equals(choice) || choice.contains("gemma") || choice.contains("deep")) {
                return TRIAGE_DEEP_GEMMA4_RCA;
            }
            return choice;
        }
        return TRIAGE_DEEP_GEMMA4_RCA;
    }

    /**
     * If commit_caused_bug.noul < .40, omit git diff from Gemma input.
     */
    public static boolean shouldIncludeGitDiff(NoulAnswer commitCausedBug) {
        if (commitCausedBug != null && commitCausedBug.noul() != null) {
            return commitCausedBug.noul() >= EVIDENCE_FILTER_THRESHOLD;
        }
        return true;
    }

    /**
     * If runbook_applies.noul < .40, omit weak runbook context.
     */
    public static boolean shouldIncludeRunbooks(NoulAnswer runbookApplies) {
        if (runbookApplies != null && runbookApplies.noul() != null) {
            return runbookApplies.noul() >= EVIDENCE_FILTER_THRESHOLD;
        }
        return true;
    }

    /**
     * Set severity by highest severity score probability level mapping 3->P0, 2->P1, 1->P2, 0->P3.
     */
    public static String mapSeverityFromScore(ScoreAnswer severityAnswer) {
        if (severityAnswer == null) {
            return "P2";
        }
        Map<String, Double> probs = severityAnswer.probabilities();
        if (probs != null && !probs.isEmpty()) {
            int highestLevel = -1;
            double maxProb = -1.0;
            for (Map.Entry<String, Double> entry : probs.entrySet()) {
                try {
                    int level = (int) Math.round(Double.parseDouble(entry.getKey().trim()));
                    Double prob = entry.getValue();
                    if (prob != null && prob > maxProb) {
                        maxProb = prob;
                        highestLevel = level;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            if (highestLevel >= 0) {
                return levelToSeverity(highestLevel);
            }
        }
        if (severityAnswer.score() != null) {
            return levelToSeverity((int) Math.round(severityAnswer.score()));
        }
        return "P2";
    }

    public static String levelToSeverity(int level) {
        return switch (level) {
            case 3 -> "P0";
            case 2 -> "P1";
            case 1 -> "P2";
            case 0 -> "P3";
            default -> "P2";
        };
    }

    /**
     * Confidence is round(severity confidence * 100).
     */
    public static int mapConfidenceFromScore(ScoreAnswer severityAnswer, int defaultConfidence) {
        if (severityAnswer != null && severityAnswer.confidence() != null) {
            return (int) Math.round(severityAnswer.confidence() * 100.0);
        }
        return defaultConfidence;
    }

    public static Map<String, DecisionQuestion> buildDefaultQuestions() {
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();

        // 1. is_actionable (Noul)
        questions.put(QUESTION_IS_ACTIONABLE, NoulQuestion.of(
                "Determine whether this log indicates an actionable operational failure requiring root-cause investigation.",
                "Actionable production failure",
                "Benign, expected, or transient noise"));

        // 2. triage_route (Choice: deep_gemma4_rca, fast_rag_only, skip_llm_noise)
        questions.put(QUESTION_TRIAGE_ROUTE, ChoiceQuestion.of(
                "Determine the appropriate triage route for this log.",
                Map.of(
                        TRIAGE_DEEP_GEMMA4_RCA, "Perform deep root cause analysis using Gemma 4 model",
                        TRIAGE_FAST_RAG_ONLY, "Perform fast triage using vector-matched runbook and RAG evidence only",
                        TRIAGE_SKIP_LLM_NOISE, "Skip LLM processing for known, transient, or benign noise")));

        // 3. commit_caused_bug (Noul)
        questions.put(QUESTION_COMMIT_CAUSED_BUG, NoulQuestion.of(
                "Did a recent git commit or code change cause this bug or regression?",
                "Recent commit caused regression",
                "Unrelated, infrastructure, or background failure"));

        // 4. runbook_applies (Noul)
        questions.put(QUESTION_RUNBOOK_APPLIES, NoulQuestion.of(
                "Does the retrieved runbook context directly apply to this incident?",
                "Runbook applies to incident",
                "Runbook is weak, irrelevant, or generic"));

        // 5. severity_score (Score: 0 to 3)
        questions.put(QUESTION_SEVERITY_SCORE, ScoreQuestion.of(
                "Rate the incident severity from 0 (P3 Minor) to 3 (P0 Outage/Critical).",
                "P3 - Minor issue or background warning",
                "P2 - Moderate non-critical error",
                "P1 - High severity customer-impacting error",
                "P0 - Critical outage or core transaction failure"));

        // 6. remediation_safety (Choice: safe_auto_rollback, requires_human_approval, escalate_dba_finance)
        questions.put(QUESTION_REMEDIATION_SAFETY, ChoiceQuestion.of(
                "Determine the operational safety of the suggested remediation action.",
                Map.of(
                        REMEDIATION_SAFE_AUTO_ROLLBACK, "Safe for automated rollback or restart without human review",
                        REMEDIATION_REQUIRES_HUMAN_APPROVAL, "Requires human approval prior to applying mitigation",
                        REMEDIATION_ESCALATE_DBA_FINANCE, "Requires escalation to DBA or finance operations team")));

        return questions;
    }

    public static String buildDecisionInput(Incident incident, RetrievalResult retrieval) {
        StringBuilder sb = new StringBuilder();
        sb.append("Service: ").append(incident.getServiceName()).append("\n");
        sb.append("Occurrences: ").append(incident.getOccurrenceCount()).append("\n");
        sb.append("Sanitized stack trace:\n").append(incident.getSanitizedStackTrace()).append("\n");

        if (retrieval != null) {
            if (retrieval.gitDiff() != null && !retrieval.gitDiff().isBlank()) {
                sb.append("\nRelevant git change:\n").append(retrieval.gitDiff()).append("\n");
            }
            if (retrieval.runbookMatches() != null && !retrieval.runbookMatches().isEmpty()) {
                sb.append("\nMatched runbooks:\n");
                for (VectorRetrieverService.RunbookMatch m : retrieval.runbookMatches()) {
                    sb.append("- ").append(m.runbookName()).append(" (similarity: ").append(m.similarity()).append(")\n");
                    sb.append(m.chunkText()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private void incrementDecisionGatedLogs(UUID tenantId) {
        if (tenantId == null || deduplicatorService == null) {
            return;
        }
        try {
            deduplicatorService.incrementDecisionGatedLogs(tenantId.toString());
        } catch (Exception ex) {
            LOGGER.warn("Failed to increment decision_gated_logs counter for tenant {}: {}", tenantId, ex.getMessage());
        }
    }

    private void recordDecisionExecution(UUID tenantId, long latencyMs) {
        if (tenantId == null || deduplicatorService == null) {
            return;
        }
        try {
            deduplicatorService.recordDecisionExecution(tenantId.toString(), latencyMs);
        } catch (Exception ex) {
            LOGGER.warn("Failed to record decision execution in Redis for tenant {}: {}", tenantId, ex.getMessage());
        }
    }

    private JsonNode buildMetadataJson(
            boolean noiseGated,
            String triageRoute,
            boolean includeGitDiff,
            boolean includeRunbooks,
            String calibratedSeverity,
            int calibratedConfidence,
            long latencyMs,
            NoulAnswer isActionable,
            ChoiceAnswer triageRouteAnswer,
            NoulAnswer commitCausedBug,
            NoulAnswer runbookApplies,
            ScoreAnswer severityScore,
            DecisionAnswer remediationSafety,
            DecisionResponse response) {

        ObjectNode root = objectMapper.createObjectNode();
        String status = noiseGated ? STATUS_IGNORED_NOISE : STATUS_PROCEED;
        root.put("status", status);
        root.put("decision", status);
        root.put("decisionType", "NOUL");
        root.put("triageRoute", triageRoute);
        root.put("calibratedSeverity", calibratedSeverity);
        root.put("calibratedConfidence", calibratedConfidence);
        root.put("gitDiffIncluded", includeGitDiff);
        root.put("runbookContextIncluded", includeRunbooks);
        root.put("noiseGated", noiseGated);
        root.put("decisionLatencyMs", latencyMs);

        ObjectNode criteria = root.putObject("criteria");
        criteria.put("noiseGatePassed", !noiseGated);
        criteria.put("triageRoute", triageRoute);
        double groundingConfidence = severityScore != null && severityScore.confidence() != null
                ? severityScore.confidence()
                : (isActionable != null && isActionable.confidence() != null ? isActionable.confidence() : 0.85);
        criteria.put("groundingConfidence", groundingConfidence);

        if (isActionable != null) {
            if (isActionable.noul() != null) criteria.put("isActionableNoul", isActionable.noul());
            if (isActionable.confidence() != null) criteria.put("isActionableConfidence", isActionable.confidence());
        }
        if (triageRouteAnswer != null && triageRouteAnswer.choice() != null) {
            criteria.put("rawTriageRouteChoice", triageRouteAnswer.choice());
        }
        if (commitCausedBug != null && commitCausedBug.noul() != null) {
            criteria.put("commitCausedBugNoul", commitCausedBug.noul());
        }
        if (runbookApplies != null && runbookApplies.noul() != null) {
            criteria.put("runbookAppliesNoul", runbookApplies.noul());
        }
        if (severityScore != null) {
            if (severityScore.score() != null) criteria.put("severityScore", severityScore.score());
            if (severityScore.confidence() != null) criteria.put("severityConfidence", severityScore.confidence());
        }

        root.put("reasoning", noiseGated
                ? "Calibrated decision gate verified incident is transient or non-actionable noise"
                : ("fast_rag_only".equalsIgnoreCase(triageRoute)
                        ? "Calibrated decision gate selected fast RAG-only triage route based on verified runbook match"
                        : "Calibrated decision gate selected deep Gemma 4 RCA route"));

        if (response != null && response.answers() != null) {
            root.set("answers", objectMapper.valueToTree(response.answers()));
        }
        if (remediationSafety != null) {
            root.set("remediationSafety", objectMapper.valueToTree(remediationSafety));
        }
        if (response != null && response.usage() != null) {
            root.set("usage", objectMapper.valueToTree(response.usage()));
        }

        root.put("evaluatedAt", Instant.now().toString());
        return root;
    }

    private GeminiDecisionGateResult parseExistingGateResult(JsonNode node) {
        String status = node.path("status").asText(STATUS_PROCEED);
        boolean noiseGated = STATUS_IGNORED_NOISE.equalsIgnoreCase(status)
                || node.path("noiseGated").asBoolean(false)
                || !node.path("criteria").path("noiseGatePassed").asBoolean(true);

        String triageRoute = node.path("triageRoute").asText(
                noiseGated ? TRIAGE_SKIP_LLM_NOISE : TRIAGE_DEEP_GEMMA4_RCA);

        boolean gitDiffIncluded = node.path("gitDiffIncluded").asBoolean(true);
        boolean runbookIncluded = node.path("runbookContextIncluded").asBoolean(true);
        String calibratedSeverity = node.path("calibratedSeverity").asText("P2");
        int calibratedConfidence = node.path("calibratedConfidence").asInt(70);
        long latencyMs = node.path("decisionLatencyMs").asLong(0L);

        DecisionAnswer remediationSafety = null;
        if (node.has("remediationSafety")) {
            try {
                remediationSafety = objectMapper.treeToValue(node.get("remediationSafety"), DecisionAnswer.class);
            } catch (Exception ignored) {
            }
        }

        return new GeminiDecisionGateResult(
                noiseGated,
                triageRoute,
                gitDiffIncluded,
                runbookIncluded,
                calibratedSeverity,
                calibratedConfidence,
                node,
                null,
                remediationSafety,
                latencyMs);
    }
}
