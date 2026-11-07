package com.airca.rag;

import com.airca.ai.TextEmbeddingService;
import com.airca.model.Incident;
import com.airca.model.RunbookChunk;
import com.airca.repository.RunbookChunkRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VectorRetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final RunbookChunkRepository runbookChunkRepository;
    private final TextEmbeddingService embeddingService;
    private final MockGitDiffService gitDiffService;

    public VectorRetrieverService(
            JdbcTemplate jdbcTemplate,
            RunbookChunkRepository runbookChunkRepository,
            TextEmbeddingService embeddingService,
            MockGitDiffService gitDiffService) {
        this.jdbcTemplate = jdbcTemplate;
        this.runbookChunkRepository = runbookChunkRepository;
        this.embeddingService = embeddingService;
        this.gitDiffService = gitDiffService;
    }

    @Transactional(readOnly = true)
    public String retrieveContext(Incident incident) {
        String queryText = incident.getServiceName() + "\n" + incident.getSanitizedStackTrace();
        String vector = TextEmbeddingService.toVectorLiteral(embeddingService.embed(queryText));
        List<RunbookMatch> vectorMatches = jdbcTemplate.query("""
                        SELECT runbook_name, chunk_text, source_path, git_diff,
                               embedding <=> ?::vector AS distance
                        FROM runbook_chunks
                        WHERE tenant_id = ? AND embedding IS NOT NULL
                        ORDER BY embedding <=> ?::vector
                        LIMIT 5
                        """,
                (resultSet, rowNumber) -> new RunbookMatch(
                        resultSet.getString("runbook_name"),
                        resultSet.getString("chunk_text"),
                        resultSet.getString("source_path"),
                        resultSet.getString("git_diff"),
                        resultSet.getDouble("distance")),
                vector,
                incident.getTenantId(),
                vector);

        List<RunbookChunk> unembeddedChunks = runbookChunkRepository
                .findAllByTenantIdOrderByCreatedAtDesc(incident.getTenantId())
                .stream()
                .filter(chunk -> vectorMatches.stream()
                        .noneMatch(match -> match.runbookName().equals(chunk.getRunbookName())))
                .filter(chunk -> lexicalScore(queryText, chunk.getChunkText()) > 0)
                .sorted(Comparator.comparingInt(
                                (RunbookChunk chunk) -> lexicalScore(queryText, chunk.getChunkText()))
                        .reversed())
                .limit(3)
                .toList();

        List<ResolvedIncidentMatch> pastIncidents = jdbcTemplate.query("""
                        SELECT service_name, sanitized_stack_trace, occurrence_count, last_seen
                        FROM incidents
                        WHERE tenant_id = ? AND status = 'RESOLVED'
                        ORDER BY last_seen DESC
                        LIMIT 100
                        """,
                (resultSet, rowNumber) -> new ResolvedIncidentMatch(
                        resultSet.getString("service_name"),
                        resultSet.getString("sanitized_stack_trace"),
                        resultSet.getLong("occurrence_count"),
                        resultSet.getTimestamp("last_seen").toInstant()),
                incident.getTenantId());
        List<ResolvedIncidentMatch> relevantIncidents = pastIncidents.stream()
                .filter(match -> lexicalScore(
                        queryText, match.serviceName() + " " + match.stackTrace()) > 0)
                .sorted(Comparator.comparingInt((ResolvedIncidentMatch match) ->
                                lexicalScore(queryText, match.serviceName() + " " + match.stackTrace()))
                        .reversed())
                .limit(3)
                .toList();

        StringBuilder context = new StringBuilder();
        context.append("Relevant git change:\n")
                .append(gitDiffService.findRelevantDiff(
                        incident.getServiceName(), incident.getSanitizedStackTrace()));
        for (RunbookMatch match : vectorMatches) {
            context.append("\n\nVector-matched runbook (cosine distance ")
                    .append(String.format(Locale.ROOT, "%.4f", match.distance()))
                    .append("): ").append(match.runbookName())
                    .append("\n").append(match.chunkText());
            if (match.sourcePath() != null && !match.sourcePath().isBlank()) {
                context.append("\nSource: ").append(match.sourcePath());
            }
            appendDiff(context, match.gitDiff());
        }
        for (RunbookChunk chunk : unembeddedChunks) {
            context.append("\n\nRunbook: ").append(chunk.getRunbookName())
                    .append("\n").append(chunk.getChunkText());
            appendDiff(context, chunk.getGitDiff());
        }
        for (ResolvedIncidentMatch match : relevantIncidents) {
            context.append("\n\nPreviously resolved incident: ")
                    .append(match.serviceName())
                    .append(" (occurrences: ").append(match.occurrenceCount())
                    .append(", resolved activity last seen: ").append(match.lastSeen()).append(")")
                    .append("\n").append(match.stackTrace());
        }
        return context.toString();
    }

    @Transactional
    public void indexResolvedIncident(UUID tenantId, String runbookName, String resolutionText) {
        String storedRunbookName = runbookName.length() <= 240
                ? runbookName
                : runbookName.substring(0, 240);
        String vector = TextEmbeddingService.toVectorLiteral(embeddingService.embed(resolutionText));
        jdbcTemplate.update("""
                        INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, source_path, embedding)
                        VALUES (?, ?, ?, ?, ?::vector)
                        """,
                tenantId,
                storedRunbookName,
                resolutionText,
                "resolved-incidents/" + storedRunbookName,
                vector);
    }

    private static int lexicalScore(String query, String candidate) {
        Set<String> queryTokens = tokens(query);
        Set<String> candidateTokens = tokens(candidate);
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) {
            return 0;
        }
        queryTokens.retainAll(candidateTokens);
        return queryTokens.size();
    }

    private static Set<String> tokens(String text) {
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 2)
                .collect(Collectors.toSet());
    }

    private static void appendDiff(StringBuilder context, String diff) {
        if (diff != null && !diff.isBlank()) {
            context.append("\nRelated change:\n").append(diff);
        }
    }

    private record RunbookMatch(
            String runbookName, String chunkText, String sourcePath, String gitDiff, double distance) {
    }

    private record ResolvedIncidentMatch(
            String serviceName, String stackTrace, long occurrenceCount, java.time.Instant lastSeen) {
    }
}
