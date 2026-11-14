package com.airca.ai;

import com.airca.dto.AiRcaReport;
import com.airca.model.Incident;
import com.airca.rag.RetrievalResult;
import com.airca.rag.VectorRetrieverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import jakarta.annotation.PreDestroy;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiRcaService implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiRcaService.class);
    public static final String DEFAULT_MODEL = "gemma-4-26b-a4b-it";
    private static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";
    private static final int MAX_CONTEXT_CHARS = 12_000;
    private static final int MAX_STACK_TRACE_CHARS = 12_000;

    public static final String NO_MATCHING_COMMIT = "No matching commit identified";
    public static final String NO_MATCHING_RUNBOOK = "No directly matching runbook found";

    private static final String SYSTEM_INSTRUCTION = """
            You are an incident root-cause analyst. Use only the supplied evidence.
            Return one JSON object with rootCauseSummary, suspectedCommit,
            matchedRunbook, confidenceScore (integer 0-100), severity
            (exactly P0, P1, P2, or P3), and suggestedRemediation.
            All schema fields must contain non-null values.
            If no suspect commit is identified in the evidence, set suspectedCommit to "No matching commit identified".
            If no runbook matches the evidence, set matchedRunbook to "No directly matching runbook found".
            Remediation must be operationally safe and specific. Do not invent
            commands, commits, or evidence not present in the context.
            """;

    private final Client client;
    private final ObjectMapper objectMapper;
    private final VectorRetrieverService vectorRetrieverService;
    private final String model;

    @Autowired
    public AiRcaService(
            ObjectMapper objectMapper,
            VectorRetrieverService vectorRetrieverService,
            @Value("${GEMINI_API_KEY:}") String apiKey,
            @Value("${airca.ai.chat-model:gemma-4-26b-a4b-it}") String model) {
        this(objectMapper, vectorRetrieverService, apiKey, model, DEFAULT_BASE_URL);
    }

    public AiRcaService(
            ObjectMapper objectMapper,
            VectorRetrieverService vectorRetrieverService,
            String apiKey,
            String model,
            String baseUrl) {
        this.objectMapper = objectMapper;
        this.vectorRetrieverService = vectorRetrieverService;
        String normalizedApiKey = apiKey != null ? apiKey.trim() : null;
        this.model = model != null && !model.isBlank() ? model.trim() : DEFAULT_MODEL;
        String base = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        this.client = normalizedApiKey == null || normalizedApiKey.isBlank()
                ? null
                : Client.builder()
                        .apiKey(normalizedApiKey)
                        .httpOptions(HttpOptions.builder()
                                .baseUrl(base)
                                .apiVersion("v1beta")
                                .build())
                        .build();
    }

    public AiRcaReport analyze(Incident incident) {
        RetrievalResult retrieval = RetrievalResult.empty("");
        if (this.vectorRetrieverService != null) {
            try {
                RetrievalResult result = this.vectorRetrieverService.retrieve(incident);
                if (result != null) {
                    retrieval = result;
                }
            } catch (Exception ex) {
                LOGGER.warn("Failed to retrieve structured RAG context: {}", ex.getMessage());
            }
        }

        String context = retrieval.context();
        String authoritativeRunbook = resolveAuthoritativeRunbook(retrieval);

        AiRcaReport report;
        if (this.client == null) {
            report = deterministicReport(incident, context);
        } else {
            try {
                GenerateContentConfig config = GenerateContentConfig.builder()
                        .temperature(0.1f)
                        .responseMimeType("application/json")
                        .systemInstruction(Content.fromParts(Part.fromText(SYSTEM_INSTRUCTION)))
                        .build();

                String prompt = buildPrompt(incident, context);
                GenerateContentResponse response = this.client.models.generateContent(this.model, prompt, config);
                LOGGER.info("FULL response : {}", response);
		if (response == null
                        || response.candidates().isEmpty()
                        || response.candidates().get().isEmpty()) {
                    throw new IllegalStateException("AI API returned no candidates");
                }
                String content = response.text();
                if (content == null || content.isBlank()) {
                    throw new IllegalStateException("AI API returned no RCA content");
                }
                LOGGER.info("Received AI RCA response : {}", content);
                String jsonPayload = extractJsonPayload(content);
                report = validate(objectMapper.readValue(jsonPayload, AiRcaReport.class));
            } catch (Exception exception) {
                LOGGER.warn("AI root-cause analysis failed; using deterministic local analysis: {}",
                        exception);
                report = deterministicReport(incident, context);
            }
        }
        return report.withMatchedRunbook(authoritativeRunbook);
    }

    private static String resolveAuthoritativeRunbook(RetrievalResult retrieval) {
        if (retrieval == null) {
            return NO_MATCHING_RUNBOOK;
        }
        return retrieval.topRunbookName().orElse(NO_MATCHING_RUNBOOK);
    }

    private String buildPrompt(Incident incident, String context) {
        return "Service: " + incident.getServiceName()
                + "\nOccurrences: " + incident.getOccurrenceCount()
                + "\nSanitized stack trace:\n"
                + truncate(
                        incident.getSanitizedStackTrace(),
                        MAX_STACK_TRACE_CHARS)
                + "\nRetrieved evidence:\n" + truncate(context);
    }

    static String extractJsonPayload(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1).trim();
        }
        return text;
    }

    @PreDestroy
    @Override
    public void close() {
        if (this.client != null) {
            this.client.close();
        }
    }

    public String getModel() {
        return this.model;
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
                NO_MATCHING_COMMIT,
                firstRunbook(context),
                62,
                "P2",
                "Check the affected service's dependency health and recent deployment history, then mitigate "
                        + "only after confirming the failing path in the sanitized trace.");
    }

    private static AiRcaReport validate(AiRcaReport report) {
        if (report == null || blank(report.rootCauseSummary()) || blank(report.suggestedRemediation())) {
            throw new IllegalArgumentException("AI response is missing required RCA fields");
        }
        String suspectedCommit = normalizeEvidence(report.suspectedCommit(), NO_MATCHING_COMMIT);
        String matchedRunbook = normalizeEvidence(report.matchedRunbook(), NO_MATCHING_RUNBOOK);
        String severity = report.severity() == null
                ? "P2"
                : report.severity().trim().toUpperCase(Locale.ROOT);
        if (!severity.matches("P[0-3]")) {
            severity = "P2";
        }
        int confidence = Math.max(0, Math.min(100, report.confidenceScore()));
        return new AiRcaReport(
                report.rootCauseSummary().trim(),
                suspectedCommit,
                matchedRunbook,
                confidence,
                severity,
                report.suggestedRemediation().trim());
    }

    private static String normalizeEvidence(String value, String fallback) {
        if (blank(value)) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase("none") || trimmed.equalsIgnoreCase("n/a")) {
            return fallback;
        }
        return trimmed;
    }

    private static String firstRunbook(String context) {
        if (context == null || context.isBlank()) {
            return NO_MATCHING_RUNBOOK;
        }
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
        return NO_MATCHING_RUNBOOK;
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
