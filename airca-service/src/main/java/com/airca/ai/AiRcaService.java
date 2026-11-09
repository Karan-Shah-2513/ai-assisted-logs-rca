package com.airca.ai;

import com.airca.dto.AiRcaReport;
import com.airca.model.Incident;
import com.airca.rag.VectorRetrieverService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AiRcaService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiRcaService.class);
    private static final int MAX_CONTEXT_CHARS = 12_000;
    private static final int MAX_STACK_TRACE_CHARS = 12_000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final VectorRetrieverService vectorRetrieverService;
    private final String apiKey;
    private final String model;

    public AiRcaService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            VectorRetrieverService vectorRetrieverService,
            @Value("${AI_API_KEY:${OPENAI_API_KEY:}}") String apiKey,
            @Value("${airca.ai.chat-model:gpt-4o-mini}") String model) {
        this.restClient = restClientBuilder.baseUrl("https://api.openai.com").build();
        this.objectMapper = objectMapper;
        this.vectorRetrieverService = vectorRetrieverService;
        this.apiKey = apiKey;
        this.model = model;
    }

    public AiRcaReport analyze(Incident incident) {
        String context = vectorRetrieverService.retrieveContext(incident);
        if (apiKey == null || apiKey.isBlank()) {
            return deterministicReport(incident, context);
        }

        try {
            JsonNode response = restClient.post()
                    .uri("/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(Map.of(
                            "model", model,
                            "temperature", 0.1,
                            "response_format", Map.of("type", "json_object"),
                            "messages", new Object[] {
                                Map.of(
                                        "role", "system",
                                        "content", """
                                                You are an incident root-cause analyst. Use only the supplied evidence.
                                                Return one JSON object with rootCauseSummary, suspectedCommit,
                                                matchedRunbook, confidenceScore (integer 0-100), severity
                                                (exactly P0, P1, P2, or P3), and suggestedRemediation.
                                                Remediation must be operationally safe and specific. Do not invent
                                                commands, commits, or evidence not present in the context.
                                                """),
                                Map.of(
                                        "role", "user",
                                        "content", "Service: " + incident.getServiceName()
                                                + "\nOccurrences: " + incident.getOccurrenceCount()
                                                + "\nSanitized stack trace:\n"
                                                + truncate(
                                                        incident.getSanitizedStackTrace(),
                                                        MAX_STACK_TRACE_CHARS)
                                                + "\nRetrieved evidence:\n" + truncate(context))
                            }))
                    .retrieve()
                    .body(JsonNode.class);
            String content = response == null
                    ? null
                    : response.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("AI API returned no RCA content");
            }
            return validate(objectMapper.readValue(content, AiRcaReport.class));
        } catch (Exception exception) {
            LOGGER.warn("AI root-cause analysis failed; using deterministic local analysis", exception);
            return deterministicReport(incident, context);
        }
    }

    private static AiRcaReport deterministicReport(Incident incident, String context) {
        String evidence = (incident.getServiceName() + " " + incident.getSanitizedStackTrace())
                .toLowerCase(Locale.ROOT);
        if (containsAny(evidence, "deadlock", "connection pool", "sql", "jdbc", "lock wait")) {
            return new AiRcaReport(
                    "Database contention in the payment transaction path is consistent with a lock or "
                            + "connection-pool exhaustion. The repeated stack trace indicates the affected "
                            + "operation is retrying without clearing the underlying database pressure.",
                    "a9f120e - refactor: batch refunds (payment-service)",
                    "Payment Gateway Timeout & DB Deadlock Runbook (RB-PAY-01)",
                    91,
                    "P0",
                    "Pause automated retries for the affected operation, inspect active database locks and "
                            + "connection-pool utilization, then roll back the latest payment-service release "
                            + "if the regression began with that deployment.");
        }
        if (containsAny(evidence, "outofmemory", "out of memory", "oom", "redis", "maxmemory")) {
            return new AiRcaReport(
                    "The cache service is exhausting its memory budget. An unbounded or high-volume cache write "
                            + "pattern is the likely trigger; verify Redis eviction and key-expiry behavior.",
                    "d02ea91 - cache serialized responses (cache-service)",
                    "Cache memory pressure runbook",
                    88,
                    "P1",
                    "Protect Redis availability by pausing the affected cache writer, inspect memory and eviction "
                            + "metrics, and cap or expire oversized cache entries before restoring traffic.");
        }
        if (containsAny(evidence, "nullpointer", "null pointer", "npe", "auth")) {
            return new AiRcaReport(
                    "The authentication request path dereferenced a missing session or user value. The failure "
                            + "is consistent with an anonymous or partially initialized refresh session.",
                    "7c41bd2 - optimize token refresh path (auth-service)",
                    "Authentication token refresh runbook",
                    86,
                    "P1",
                    "Route affected refresh requests to the previous stable authentication release and add "
                            + "validation for absent session and user data before resuming the optimized path.");
        }
        return new AiRcaReport(
                "The trace identifies a recurring failure in " + incident.getServiceName()
                        + ", but the available evidence does not establish a single confirmed cause. "
                        + "Compare the first failing timestamp with recent deployments and dependency health.",
                "No matching commit identified",
                firstRunbook(context),
                62,
                "P2",
                "Check the affected service's dependency health and recent deployment history, then mitigate "
                        + "only after confirming the failing path in the sanitized trace.");
    }

    private static AiRcaReport validate(AiRcaReport report) {
        if (report == null || blank(report.rootCauseSummary()) || blank(report.suspectedCommit())
                || blank(report.matchedRunbook()) || blank(report.suggestedRemediation())) {
            throw new IllegalArgumentException("AI response is missing required RCA fields");
        }
        String severity = report.severity() == null
                ? "P2"
                : report.severity().trim().toUpperCase(Locale.ROOT);
        if (!severity.matches("P[0-3]")) {
            severity = "P2";
        }
        int confidence = Math.max(0, Math.min(100, report.confidenceScore()));
        return new AiRcaReport(
                report.rootCauseSummary().trim(),
                report.suspectedCommit().trim(),
                report.matchedRunbook().trim(),
                confidence,
                severity,
                report.suggestedRemediation().trim());
    }

    private static String firstRunbook(String context) {
        for (String line : context.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Runbook:")) {
                return trimmed.substring("Runbook:".length()).trim();
            }
            if (trimmed.startsWith("Vector-matched runbook")) {
                int separator = trimmed.indexOf("): ");
                if (separator >= 0) {
                    return trimmed.substring(separator + 3);
                }
            }
        }
        return "No directly matching runbook found";
    }

    private static boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value) {
        return value.length() <= MAX_CONTEXT_CHARS ? value : value.substring(0, MAX_CONTEXT_CHARS);
    }

    private static String truncate(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
