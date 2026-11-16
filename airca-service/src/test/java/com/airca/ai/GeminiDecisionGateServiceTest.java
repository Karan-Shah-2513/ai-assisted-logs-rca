package com.airca.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.airca.deduplication.RedisDeduplicatorService;
import com.airca.dto.ChoiceAnswer;
import com.airca.dto.ChoiceQuestion;
import com.airca.dto.DecisionAnswer;
import com.airca.dto.DecisionQuestion;
import com.airca.dto.DecisionRequest;
import com.airca.dto.DecisionResponse;
import com.airca.dto.NoulAnswer;
import com.airca.dto.NoulQuestion;
import com.airca.dto.ScoreAnswer;
import com.airca.dto.ScoreQuestion;
import com.airca.model.Incident;
import com.airca.rag.RetrievalResult;
import com.airca.rag.VectorRetrieverService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GeminiDecisionGateServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GeminiDecisionClient decisionClient;
    private RedisDeduplicatorService deduplicatorService;
    private GeminiDecisionGateService gateService;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        decisionClient = mock(GeminiDecisionClient.class);
        deduplicatorService = mock(RedisDeduplicatorService.class);
        gateService = new GeminiDecisionGateService(decisionClient, deduplicatorService, objectMapper);
    }

    private Incident createIncident(String serviceName, String stackTrace) {
        Incident incident = new Incident();
        incident.setId(UUID.randomUUID());
        incident.setTenantId(tenantId);
        incident.setFingerprintHash("fp-" + UUID.randomUUID());
        incident.setServiceName(serviceName);
        incident.setRawStackTrace(stackTrace);
        incident.setSanitizedStackTrace(stackTrace);
        incident.setOccurrenceCount(1);
        incident.setFirstSeen(Instant.now());
        incident.setLastSeen(Instant.now());
        return incident;
    }

    private RetrievalResult createRetrievalResult(String diff, String runbookName, String runbookText) {
        List<VectorRetrieverService.RunbookMatch> matches = runbookName != null
                ? List.of(new VectorRetrieverService.RunbookMatch(runbookName, runbookText, "runbooks/" + runbookName, diff, 0.1, 0.9))
                : Collections.emptyList();
        return new RetrievalResult("context", matches, Collections.emptyList(), diff);
    }

    @Test
    @DisplayName("Assert exact contract for all six decision questions: IDs, types, and options")
    void shouldAssertExactSixDecisionQuestionsContract() {
        Map<String, DecisionQuestion> questions = GeminiDecisionGateService.buildDefaultQuestions();

        assertEquals(6, questions.size(), "Must specify exactly six decision questions");

        // 1. is_actionable (NoulQuestion)
        assertTrue(questions.containsKey(GeminiDecisionGateService.QUESTION_IS_ACTIONABLE));
        assertInstanceOf(NoulQuestion.class, questions.get(GeminiDecisionGateService.QUESTION_IS_ACTIONABLE));

        // 2. triage_route (ChoiceQuestion with deep_gemma4_rca, fast_rag_only, skip_llm_noise)
        assertTrue(questions.containsKey(GeminiDecisionGateService.QUESTION_TRIAGE_ROUTE));
        DecisionQuestion triageQ = questions.get(GeminiDecisionGateService.QUESTION_TRIAGE_ROUTE);
        assertInstanceOf(ChoiceQuestion.class, triageQ);
        ChoiceQuestion triageChoice = (ChoiceQuestion) triageQ;
        assertThat(triageChoice.criteria().keySet()).containsExactlyInAnyOrder(
                GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA,
                GeminiDecisionGateService.TRIAGE_FAST_RAG_ONLY,
                GeminiDecisionGateService.TRIAGE_SKIP_LLM_NOISE);

        // 3. commit_caused_bug (NoulQuestion)
        assertTrue(questions.containsKey(GeminiDecisionGateService.QUESTION_COMMIT_CAUSED_BUG));
        assertInstanceOf(NoulQuestion.class, questions.get(GeminiDecisionGateService.QUESTION_COMMIT_CAUSED_BUG));

        // 4. runbook_applies (NoulQuestion)
        assertTrue(questions.containsKey(GeminiDecisionGateService.QUESTION_RUNBOOK_APPLIES));
        assertInstanceOf(NoulQuestion.class, questions.get(GeminiDecisionGateService.QUESTION_RUNBOOK_APPLIES));

        // 5. severity_score (ScoreQuestion)
        assertTrue(questions.containsKey(GeminiDecisionGateService.QUESTION_SEVERITY_SCORE));
        DecisionQuestion severityQ = questions.get(GeminiDecisionGateService.QUESTION_SEVERITY_SCORE);
        assertInstanceOf(ScoreQuestion.class, severityQ);
        ScoreQuestion scoreQ = (ScoreQuestion) severityQ;
        assertEquals(4, scoreQ.criteria().size(), "severity_score must define 4 score criteria levels (0 to 3)");

        // 6. remediation_safety (ChoiceQuestion with safe_auto_rollback, requires_human_approval, escalate_dba_finance)
        assertTrue(questions.containsKey(GeminiDecisionGateService.QUESTION_REMEDIATION_SAFETY));
        DecisionQuestion remQ = questions.get(GeminiDecisionGateService.QUESTION_REMEDIATION_SAFETY);
        assertInstanceOf(ChoiceQuestion.class, remQ);
        ChoiceQuestion remChoice = (ChoiceQuestion) remQ;
        assertThat(remChoice.criteria().keySet()).containsExactlyInAnyOrder(
                GeminiDecisionGateService.REMEDIATION_SAFE_AUTO_ROLLBACK,
                GeminiDecisionGateService.REMEDIATION_REQUIRES_HUMAN_APPROVAL,
                GeminiDecisionGateService.REMEDIATION_ESCALATE_DBA_FINANCE);
    }

    @Test
    @DisplayName("Noise gate should trigger when is_actionable.noul < 0.25 and confidence >= 0.80")
    void shouldTriggerNoiseGateWhenIsActionableBelowThresholdWithHighConfidence() {
        NoulAnswer isActionable = new NoulAnswer(0.18, 0.85);
        ChoiceAnswer triageRoute = new ChoiceAnswer(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, 0.90, Map.of(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, 0.90));

        assertTrue(GeminiDecisionGateService.isNoiseGated(isActionable, triageRoute));
    }

    @Test
    @DisplayName("Noise gate should trigger when triage_route.choice == skip_llm_noise regardless of is_actionable")
    void shouldTriggerNoiseGateWhenTriageRouteIsSkipLlmNoise() {
        NoulAnswer isActionable = new NoulAnswer(0.85, 0.92);
        ChoiceAnswer triageRoute = new ChoiceAnswer(GeminiDecisionGateService.TRIAGE_SKIP_LLM_NOISE, 0.95, Map.of(GeminiDecisionGateService.TRIAGE_SKIP_LLM_NOISE, 0.95));

        assertTrue(GeminiDecisionGateService.isNoiseGated(isActionable, triageRoute));
    }

    @Test
    @DisplayName("Noise gate should NOT trigger when is_actionable >= 0.25 and route is deep_gemma4_rca")
    void shouldNotTriggerNoiseGateForActionableIncident() {
        NoulAnswer isActionable = new NoulAnswer(0.92, 0.88);
        ChoiceAnswer triageRoute = new ChoiceAnswer(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, 0.90, Map.of(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, 0.90));

        assertFalse(GeminiDecisionGateService.isNoiseGated(isActionable, triageRoute));
    }

    @Test
    @DisplayName("Noise gate should NOT trigger when confidence is below 0.80 even if noul < 0.25")
    void shouldNotTriggerNoiseGateWhenConfidenceBelowThreshold() {
        NoulAnswer isActionable = new NoulAnswer(0.15, 0.75); // low confidence
        ChoiceAnswer triageRoute = new ChoiceAnswer(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, 0.70, Map.of(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, 0.70));

        assertFalse(GeminiDecisionGateService.isNoiseGated(isActionable, triageRoute));
    }

    @Test
    @DisplayName("Evidence filter: omit git diff when commit_caused_bug.noul < 0.40")
    void shouldOmitGitDiffWhenCommitCausedBugBelowThreshold() {
        assertFalse(GeminiDecisionGateService.shouldIncludeGitDiff(new NoulAnswer(0.35, 0.82)));
        assertFalse(GeminiDecisionGateService.shouldIncludeGitDiff(new NoulAnswer(0.05, 0.90)));
        assertTrue(GeminiDecisionGateService.shouldIncludeGitDiff(new NoulAnswer(0.40, 0.85)));
        assertTrue(GeminiDecisionGateService.shouldIncludeGitDiff(new NoulAnswer(0.75, 0.88)));
        assertTrue(GeminiDecisionGateService.shouldIncludeGitDiff(null));
    }

    @Test
    @DisplayName("Evidence filter: omit runbook context when runbook_applies.noul < 0.40")
    void shouldOmitRunbookContextWhenRunbookAppliesBelowThreshold() {
        assertFalse(GeminiDecisionGateService.shouldIncludeRunbooks(new NoulAnswer(0.39, 0.80)));
        assertFalse(GeminiDecisionGateService.shouldIncludeRunbooks(new NoulAnswer(0.10, 0.95)));
        assertTrue(GeminiDecisionGateService.shouldIncludeRunbooks(new NoulAnswer(0.40, 0.80)));
        assertTrue(GeminiDecisionGateService.shouldIncludeRunbooks(new NoulAnswer(0.85, 0.90)));
        assertTrue(GeminiDecisionGateService.shouldIncludeRunbooks(null));
    }

    @Test
    @DisplayName("Severity mapping: 3->P0, 2->P1, 1->P2, 0->P3 based on highest probability level and confidence rounded")
    void shouldMapSeverityByHighestProbabilityLevel() {
        // Level 3 has highest probability -> P0
        ScoreAnswer answerP0 = new ScoreAnswer(3.0, 0.95,
                Map.of("0", 0.05, "1", 0.05, "2", 0.10, "3", 0.80),
                Map.of("0", "P3", "1", "P2", "2", "P1", "3", "P0"));
        assertEquals("P0", GeminiDecisionGateService.mapSeverityFromScore(answerP0));
        assertEquals(95, GeminiDecisionGateService.mapConfidenceFromScore(answerP0, 70));

        // Level 2 has highest probability -> P1
        ScoreAnswer answerP1 = new ScoreAnswer(2.0, 0.884,
                Map.of("0", 0.05, "1", 0.15, "2", 0.70, "3", 0.10),
                Map.of("0", "P3", "1", "P2", "2", "P1", "3", "P0"));
        assertEquals("P1", GeminiDecisionGateService.mapSeverityFromScore(answerP1));
        assertEquals(88, GeminiDecisionGateService.mapConfidenceFromScore(answerP1, 70));

        // Level 1 has highest probability -> P2
        ScoreAnswer answerP2 = new ScoreAnswer(1.0, 0.826,
                Map.of("0", 0.10, "1", 0.65, "2", 0.20, "3", 0.05),
                Map.of("0", "P3", "1", "P2", "2", "P1", "3", "P0"));
        assertEquals("P2", GeminiDecisionGateService.mapSeverityFromScore(answerP2));
        assertEquals(83, GeminiDecisionGateService.mapConfidenceFromScore(answerP2, 70));

        // Level 0 has highest probability -> P3
        ScoreAnswer answerP3 = new ScoreAnswer(0.0, 0.78,
                Map.of("0", 0.85, "1", 0.05, "2", 0.05, "3", 0.05),
                Map.of("0", "P3", "1", "P2", "2", "P1", "3", "P0"));
        assertEquals("P3", GeminiDecisionGateService.mapSeverityFromScore(answerP3));
        assertEquals(78, GeminiDecisionGateService.mapConfidenceFromScore(answerP3, 70));

        // Missing probabilities fallback to score
        ScoreAnswer fallbackScore = new ScoreAnswer(2.0, 0.90, Map.of(), Map.of());
        assertEquals("P1", GeminiDecisionGateService.mapSeverityFromScore(fallbackScore));
    }

    @Test
    @DisplayName("Evaluate noise-gated incident: increments Redis counter, sets skip_llm_noise triage route")
    void testEvaluateNoiseGatedIncident() {
        Incident incident = createIncident("order-service", "INFO: Scheduled ping completed in 2ms");
        RetrievalResult retrieval = createRetrievalResult("", null, null);

        DecisionResponse decisionResponse = new DecisionResponse(
                Map.of(
                        GeminiDecisionGateService.QUESTION_IS_ACTIONABLE, new NoulAnswer(0.10, 0.92),
                        GeminiDecisionGateService.QUESTION_TRIAGE_ROUTE, new ChoiceAnswer(GeminiDecisionGateService.TRIAGE_SKIP_LLM_NOISE, 0.94, Map.of(GeminiDecisionGateService.TRIAGE_SKIP_LLM_NOISE, 0.94)),
                        GeminiDecisionGateService.QUESTION_COMMIT_CAUSED_BUG, new NoulAnswer(0.10, 0.90),
                        GeminiDecisionGateService.QUESTION_RUNBOOK_APPLIES, new NoulAnswer(0.05, 0.90),
                        GeminiDecisionGateService.QUESTION_SEVERITY_SCORE, new ScoreAnswer(0.0, 0.85, Map.of("0", 0.90, "1", 0.05, "2", 0.03, "3", 0.02), Map.of()),
                        GeminiDecisionGateService.QUESTION_REMEDIATION_SAFETY, new ChoiceAnswer(GeminiDecisionGateService.REMEDIATION_SAFE_AUTO_ROLLBACK, 0.95, Map.of(GeminiDecisionGateService.REMEDIATION_SAFE_AUTO_ROLLBACK, 0.95))),
                null,
                null);

        when(decisionClient.decide(any())).thenReturn(decisionResponse);

        GeminiDecisionGateResult result = gateService.evaluate(incident, retrieval);

        assertTrue(result.isNoiseGated());
        assertEquals(GeminiDecisionGateService.TRIAGE_SKIP_LLM_NOISE, result.triageRoute());
        assertEquals("P3", result.calibratedSeverity());
        assertEquals(85, result.calibratedConfidence());
        assertFalse(result.isIncludeGitDiff());
        assertFalse(result.isIncludeRunbooks());

        // Verify Redis decision_gated_logs counter was incremented
        verify(deduplicatorService).incrementDecisionGatedLogs(tenantId.toString());

        // Verify decisionGateJson persisted on incident entity
        JsonNode gateJson = incident.getDecisionGateJson();
        assertNotNull(gateJson);
        assertEquals("IGNORED_NOISE", gateJson.get("status").asText());
        assertEquals("IGNORED_NOISE", gateJson.get("decision").asText());
        assertEquals(GeminiDecisionGateService.TRIAGE_SKIP_LLM_NOISE, gateJson.get("triageRoute").asText());
        assertFalse(gateJson.get("criteria").get("noiseGatePassed").asBoolean());
        assertEquals("P3", gateJson.get("calibratedSeverity").asText());
        assertEquals(85, gateJson.get("calibratedConfidence").asInt());

        // Verify remediation safety is preserved for later DTO/UI
        assertNotNull(result.remediationSafety());
        assertNotNull(gateJson.get("remediationSafety"));
    }

    @Test
    @DisplayName("Evaluate actionable incident with fast_rag_only triage route: exposes fast_rag_only in result")
    void testEvaluateFastRagOnlyIncident() {
        Incident incident = createIncident("order-service", "java.sql.SQLException: Deadlock detected");
        RetrievalResult retrieval = createRetrievalResult("diff --git a/Order.java", "DB Deadlock Runbook", "Resolve DB lock");

        DecisionResponse decisionResponse = new DecisionResponse(
                Map.of(
                        GeminiDecisionGateService.QUESTION_IS_ACTIONABLE, new NoulAnswer(0.96, 0.94),
                        GeminiDecisionGateService.QUESTION_TRIAGE_ROUTE, new ChoiceAnswer(GeminiDecisionGateService.TRIAGE_FAST_RAG_ONLY, 0.91, Map.of(GeminiDecisionGateService.TRIAGE_FAST_RAG_ONLY, 0.91)),
                        GeminiDecisionGateService.QUESTION_COMMIT_CAUSED_BUG, new NoulAnswer(0.85, 0.88),
                        GeminiDecisionGateService.QUESTION_RUNBOOK_APPLIES, new NoulAnswer(0.88, 0.90),
                        GeminiDecisionGateService.QUESTION_SEVERITY_SCORE, new ScoreAnswer(3.0, 0.92, Map.of("0", 0.02, "1", 0.03, "2", 0.15, "3", 0.80), Map.of()),
                        GeminiDecisionGateService.QUESTION_REMEDIATION_SAFETY, new ChoiceAnswer(GeminiDecisionGateService.REMEDIATION_REQUIRES_HUMAN_APPROVAL, 0.92, Map.of(GeminiDecisionGateService.REMEDIATION_REQUIRES_HUMAN_APPROVAL, 0.92))),
                null,
                null);

        when(decisionClient.decide(any())).thenReturn(decisionResponse);

        GeminiDecisionGateResult result = gateService.evaluate(incident, retrieval);

        assertFalse(result.isNoiseGated());
        assertEquals(GeminiDecisionGateService.TRIAGE_FAST_RAG_ONLY, result.triageRoute());
        assertTrue(result.isFastRagOnly());
        assertFalse(result.isDeepGemma4Rca());
        assertEquals("P0", result.calibratedSeverity());
        assertEquals(92, result.calibratedConfidence());

        // Counter is NOT incremented
        verify(deduplicatorService, never()).incrementDecisionGatedLogs(any());

        JsonNode gateJson = incident.getDecisionGateJson();
        assertNotNull(gateJson);
        assertEquals("PROCEED", gateJson.get("status").asText());
        assertEquals(GeminiDecisionGateService.TRIAGE_FAST_RAG_ONLY, gateJson.get("triageRoute").asText());
    }

    @Test
    @DisplayName("Evaluate actionable incident with deep_gemma4_rca route: exposes deep_gemma4_rca")
    void testEvaluateDeepGemma4RcaIncident() {
        Incident incident = createIncident("order-service", "java.sql.SQLException: Deadlock detected");
        RetrievalResult retrieval = createRetrievalResult("diff --git a/Order.java", "DB Deadlock Runbook", "Resolve DB lock");

        DecisionResponse decisionResponse = new DecisionResponse(
                Map.of(
                        GeminiDecisionGateService.QUESTION_IS_ACTIONABLE, new NoulAnswer(0.96, 0.94),
                        GeminiDecisionGateService.QUESTION_TRIAGE_ROUTE, new ChoiceAnswer(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, 0.92, Map.of(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, 0.92)),
                        GeminiDecisionGateService.QUESTION_COMMIT_CAUSED_BUG, new NoulAnswer(0.85, 0.88),
                        GeminiDecisionGateService.QUESTION_RUNBOOK_APPLIES, new NoulAnswer(0.88, 0.90),
                        GeminiDecisionGateService.QUESTION_SEVERITY_SCORE, new ScoreAnswer(3.0, 0.92, Map.of("0", 0.02, "1", 0.03, "2", 0.15, "3", 0.80), Map.of()),
                        GeminiDecisionGateService.QUESTION_REMEDIATION_SAFETY, new ChoiceAnswer(GeminiDecisionGateService.REMEDIATION_ESCALATE_DBA_FINANCE, 0.89, Map.of(GeminiDecisionGateService.REMEDIATION_ESCALATE_DBA_FINANCE, 0.89))),
                null,
                null);

        when(decisionClient.decide(any())).thenReturn(decisionResponse);

        GeminiDecisionGateResult result = gateService.evaluate(incident, retrieval);

        assertFalse(result.isNoiseGated());
        assertEquals(GeminiDecisionGateService.TRIAGE_DEEP_GEMMA4_RCA, result.triageRoute());
        assertTrue(result.isDeepGemma4Rca());
        assertFalse(result.isFastRagOnly());
        assertEquals("P0", result.calibratedSeverity());
        assertEquals(92, result.calibratedConfidence());
        assertTrue(result.isIncludeGitDiff());
        assertTrue(result.isIncludeRunbooks());

        verify(deduplicatorService, never()).incrementDecisionGatedLogs(any());
    }

    @Test
    @DisplayName("Idempotency: Reuses existing decisionGateJson without invoking decision client again")
    void testIdempotentDecisionEvaluation() {
        Incident incident = createIncident("order-service", "java.lang.NullPointerException");
        String existingJson = """
                {
                    "status": "PROCEED",
                    "decision": "PROCEED",
                    "triageRoute": "fast_rag_only",
                    "calibratedSeverity": "P1",
                    "calibratedConfidence": 88,
                    "gitDiffIncluded": true,
                    "runbookContextIncluded": false,
                    "criteria": {
                        "noiseGatePassed": true,
                        "groundingConfidence": 0.88
                    }
                }
                """;
        try {
            incident.setDecisionGateJson(objectMapper.readTree(existingJson));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        RetrievalResult retrieval = createRetrievalResult("", null, null);
        GeminiDecisionGateResult result = gateService.evaluate(incident, retrieval);

        assertFalse(result.isNoiseGated());
        assertEquals("fast_rag_only", result.triageRoute());
        assertTrue(result.isFastRagOnly());
        assertEquals("P1", result.calibratedSeverity());
        assertEquals(88, result.calibratedConfidence());
        assertTrue(result.isIncludeGitDiff());
        assertFalse(result.isIncludeRunbooks());

        // Verify client was never called
        verify(decisionClient, never()).decide(any());
        verify(deduplicatorService, never()).incrementDecisionGatedLogs(any());
    }
}
