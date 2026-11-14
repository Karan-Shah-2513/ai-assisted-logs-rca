package com.airca.rag;

import com.airca.ai.TextEmbeddingService;
import com.airca.model.Incident;
import com.airca.model.RunbookChunk;
import com.airca.repository.RunbookChunkRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VectorRetrieverService {

    public static final double DEFAULT_MIN_SIMILARITY = 0.72;

    private final JdbcTemplate jdbcTemplate;
    private final RunbookChunkRepository runbookChunkRepository;
    private final TextEmbeddingService embeddingService;
    private final GitCommitInspectorService gitCommitInspectorService;
    private final double minSimilarity;

    @Autowired
    public VectorRetrieverService(
            JdbcTemplate jdbcTemplate,
            RunbookChunkRepository runbookChunkRepository,
            TextEmbeddingService embeddingService,
            GitCommitInspectorService gitCommitInspectorService,
            @Value("${app.ai.rag.min-similarity:0.72}") double minSimilarity) {
        this.jdbcTemplate = jdbcTemplate;
        this.runbookChunkRepository = runbookChunkRepository;
        this.embeddingService = embeddingService;
        this.gitCommitInspectorService = gitCommitInspectorService;
        this.minSimilarity = minSimilarity;
    }

    public VectorRetrieverService(
            JdbcTemplate jdbcTemplate,
            RunbookChunkRepository runbookChunkRepository,
            TextEmbeddingService embeddingService,
            GitCommitInspectorService gitCommitInspectorService) {
        this(jdbcTemplate, runbookChunkRepository, embeddingService, gitCommitInspectorService, DEFAULT_MIN_SIMILARITY);
    }

    @Transactional
    public void ensureRunbooksIndexed(UUID tenantId) {
        List<Map<String, Object>> unembedded = jdbcTemplate.queryForList("""
                SELECT id, chunk_text
                FROM runbook_chunks
                WHERE tenant_id = ? AND embedding IS NULL
                """, tenantId);

        for (Map<String, Object> row : unembedded) {
            Object idObj = row.get("id");
            UUID id = idObj instanceof UUID uuid ? uuid : UUID.fromString(idObj.toString());
            String chunkText = (String) row.get("chunk_text");
            if (chunkText != null && !chunkText.isBlank()) {
                String vectorLiteral = embeddingService.toVectorLiteral(embeddingService.embed(chunkText));
                jdbcTemplate.update("""
                        UPDATE runbook_chunks
                        SET embedding = ?::vector
                        WHERE id = ? AND tenant_id = ?
                        """, vectorLiteral, id, tenantId);
            }
        }
    }

    @Transactional
    public RetrievalResult retrieve(Incident incident) {
        ensureRunbooksIndexed(incident.getTenantId());

        String queryText = incident.getServiceName() + "\n" + incident.getSanitizedStackTrace();
        String vector = embeddingService.toVectorLiteral(embeddingService.embed(queryText));

        List<RunbookMatch> vectorMatches = jdbcTemplate.query("""
                        SELECT runbook_name, chunk_text, source_path, git_diff,
                               (embedding <=> ?::vector) AS distance,
                               (1.0 - (embedding <=> ?::vector)) AS similarity
                        FROM runbook_chunks
                        WHERE tenant_id = ?
                          AND embedding IS NOT NULL
                          AND (1.0 - (embedding <=> ?::vector)) >= ?
                        ORDER BY embedding <=> ?::vector ASC
                        LIMIT 5
                        """,
                (resultSet, rowNumber) -> new RunbookMatch(
                        resultSet.getString("runbook_name"),
                        resultSet.getString("chunk_text"),
                        resultSet.getString("source_path"),
                        resultSet.getString("git_diff"),
                        resultSet.getDouble("distance"),
                        resultSet.getDouble("similarity")),
                vector,
                vector,
                incident.getTenantId(),
                vector,
                minSimilarity,
                vector);

        List<ResolvedIncidentMatch> pastIncidents = jdbcTemplate.query("""
                        SELECT service_name, sanitized_stack_trace, occurrence_count, last_seen,
                               (embedding <=> ?::vector) AS distance,
                               (1.0 - (embedding <=> ?::vector)) AS similarity
                        FROM incidents
                        WHERE tenant_id = ?
                          AND status = 'RESOLVED'
                          AND embedding IS NOT NULL
                          AND (1.0 - (embedding <=> ?::vector)) >= ?
                        ORDER BY embedding <=> ?::vector ASC
                        LIMIT 5
                        """,
                (resultSet, rowNumber) -> new ResolvedIncidentMatch(
                        resultSet.getString("service_name"),
                        resultSet.getString("sanitized_stack_trace"),
                        resultSet.getLong("occurrence_count"),
                        resultSet.getTimestamp("last_seen").toInstant(),
                        resultSet.getDouble("distance"),
                        resultSet.getDouble("similarity")),
                vector,
                vector,
                incident.getTenantId(),
                vector,
                minSimilarity,
                vector);

        String relevantDiff = gitCommitInspectorService.findRelevantDiff(
                incident.getServiceName(), incident.getSanitizedStackTrace());

        StringBuilder context = new StringBuilder();
        context.append("Relevant git change:\n").append(relevantDiff);

        for (RunbookMatch match : vectorMatches) {
            context.append("\n\nVector-matched runbook (similarity ")
                    .append(String.format(Locale.ROOT, "%.4f", match.similarity()))
                    .append(", cosine distance ")
                    .append(String.format(Locale.ROOT, "%.4f", match.distance()))
                    .append("): ").append(match.runbookName())
                    .append("\n").append(match.chunkText());
            if (match.sourcePath() != null && !match.sourcePath().isBlank()) {
                context.append("\nSource: ").append(match.sourcePath());
            }
            appendDiff(context, match.gitDiff());
        }

        for (ResolvedIncidentMatch match : pastIncidents) {
            context.append("\n\nPreviously resolved incident (similarity ")
                    .append(String.format(Locale.ROOT, "%.4f", match.similarity()))
                    .append(", cosine distance ")
                    .append(String.format(Locale.ROOT, "%.4f", match.distance()))
                    .append("): ")
                    .append(match.serviceName())
                    .append(" (occurrences: ").append(match.occurrenceCount())
                    .append(", resolved activity last seen: ").append(match.lastSeen()).append(")")
                    .append("\n").append(match.stackTrace());
        }
        return new RetrievalResult(context.toString(), vectorMatches, pastIncidents, relevantDiff);
    }

    @Transactional
    public String retrieveContext(Incident incident) {
        RetrievalResult result = retrieve(incident);
        return result != null ? result.context() : "";
    }

    @Transactional
    public void indexResolvedIncident(
            UUID tenantId,
            UUID incidentId,
            String runbookName,
            String resolutionText,
            String source) {
        String storedRunbookName = runbookName.length() <= 240
                ? runbookName
                : runbookName.substring(0, 240);
        String resolvedSource = (source != null && !source.isBlank())
                ? source
                : RunbookChunk.SOURCE_HUMAN_VERIFIED_INCIDENT;
        String vector = embeddingService.toVectorLiteral(embeddingService.embed(resolutionText));
        jdbcTemplate.update("""
                        INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, source_path, source, source_incident_id, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?::vector)
                        ON CONFLICT (tenant_id, source_incident_id) DO UPDATE SET
                            runbook_name = EXCLUDED.runbook_name,
                            chunk_text = EXCLUDED.chunk_text,
                            source_path = EXCLUDED.source_path,
                            source = EXCLUDED.source,
                            embedding = EXCLUDED.embedding
                        """,
                tenantId,
                storedRunbookName,
                resolutionText,
                "resolved-incidents/" + storedRunbookName,
                resolvedSource,
                incidentId,
                vector);
    }

    @Transactional
    public void indexResolvedIncident(
            UUID tenantId,
            UUID incidentId,
            String runbookName,
            String resolutionText) {
        indexResolvedIncident(tenantId, incidentId, runbookName, resolutionText, RunbookChunk.SOURCE_HUMAN_VERIFIED_INCIDENT);
    }

    @Transactional
    public void indexResolvedIncident(UUID tenantId, String runbookName, String resolutionText) {
        indexResolvedIncident(tenantId, null, runbookName, resolutionText, RunbookChunk.SOURCE_HUMAN_VERIFIED_INCIDENT);
    }

    public double getMinSimilarity() {
        return minSimilarity;
    }

    private static void appendDiff(StringBuilder context, String diff) {
        if (diff != null && !diff.isBlank()) {
            context.append("\nRelated change:\n").append(diff);
        }
    }

    public record RunbookMatch(
            String runbookName,
            String chunkText,
            String sourcePath,
            String gitDiff,
            double distance,
            double similarity) {
    }

    public record ResolvedIncidentMatch(
            String serviceName,
            String stackTrace,
            long occurrenceCount,
            java.time.Instant lastSeen,
            double distance,
            double similarity) {
    }
}
