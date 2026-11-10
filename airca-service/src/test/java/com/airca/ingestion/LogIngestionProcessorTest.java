package com.airca.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.airca.ai.AiRcaService;
import com.airca.ai.TextEmbeddingService;
import com.airca.deduplication.RedisDeduplicatorService;
import com.airca.dto.AiRcaReport;
import com.airca.model.Incident;
import com.airca.model.IncidentStatus;
import com.airca.repository.IncidentRepository;
import com.airca.websocket.StompBroadcasterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogIngestionProcessorTest {

    private RedisDeduplicatorService deduplicatorService;
    private LogSanitizer sanitizer;
    private IncidentRepository incidentRepository;
    private AiRcaService aiRcaService;
    private ObjectMapper objectMapper;
    private StompBroadcasterService broadcasterService;
    private TextEmbeddingService embeddingService;
    private LogIngestionProcessor processor;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void createsSpringBeanUsingAutowiredConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(LogIngestionProcessor.class)
                .withBean(RedisDeduplicatorService.class, () -> mock(RedisDeduplicatorService.class))
                .withBean(LogSanitizer.class, () -> mock(LogSanitizer.class))
                .withBean(IncidentRepository.class, () -> mock(IncidentRepository.class))
                .withBean(AiRcaService.class, () -> mock(AiRcaService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(StompBroadcasterService.class, () -> mock(StompBroadcasterService.class))
                .withBean(TextEmbeddingService.class, () -> mock(TextEmbeddingService.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LogIngestionProcessor.class);
                });
    }

    @BeforeEach
    void setUp() {
        deduplicatorService = mock(RedisDeduplicatorService.class);
        sanitizer = mock(LogSanitizer.class);
        incidentRepository = mock(IncidentRepository.class);
        aiRcaService = mock(AiRcaService.class);
        objectMapper = new ObjectMapper();
        broadcasterService = mock(StompBroadcasterService.class);
        embeddingService = mock(TextEmbeddingService.class);

        processor = new LogIngestionProcessor(
                deduplicatorService,
                sanitizer,
                incidentRepository,
                aiRcaService,
                objectMapper,
                broadcasterService,
                embeddingService);
    }

    @Test
    void generatesAndPersistsEmbeddingOnFirstRedisOccurrence() {
        String rawTrace = "java.sql.SQLException: connection pool timeout\n at pool.getConnection(12)";
        String sanitizedTrace = "java.sql.SQLException: connection pool timeout\n at pool.getConnection(<line>)";
        String fingerprint = "abc123hash";
        LogMessage message = new LogMessage(tenantId, "order-service", rawTrace, "corr-1", Instant.now());

        when(sanitizer.sanitize(rawTrace)).thenReturn(sanitizedTrace);
        when(deduplicatorService.fingerprint(rawTrace)).thenReturn(fingerprint);
        when(deduplicatorService.record(tenantId.toString(), fingerprint))
                .thenReturn(new RedisDeduplicatorService.DeduplicationResult(fingerprint, 1, 0, 1));

        String expectedTextToEmbed = "order-service\n" + sanitizedTrace;
        List<Double> dummyVector = Collections.nCopies(768, 0.03);
        String vectorLiteral = "[0.03,0.03]";
        when(embeddingService.embed(expectedTextToEmbed)).thenReturn(dummyVector);
        when(embeddingService.toVectorLiteral(dummyVector)).thenReturn(vectorLiteral);

        Incident persistedIncident = new Incident();
        persistedIncident.setId(UUID.randomUUID());
        persistedIncident.setTenantId(tenantId);
        persistedIncident.setFingerprintHash(fingerprint);
        persistedIncident.setServiceName("order-service");
        persistedIncident.setRawStackTrace(rawTrace);
        persistedIncident.setSanitizedStackTrace(sanitizedTrace);
        persistedIncident.setOccurrenceCount(1);
        persistedIncident.setStatus(IncidentStatus.OPEN);
        persistedIncident.setFirstSeen(Instant.now());
        persistedIncident.setLastSeen(Instant.now());

        when(incidentRepository.findByTenantIdAndFingerprintHash(tenantId, fingerprint))
                .thenReturn(Optional.of(persistedIncident));
        when(incidentRepository.countByTenantId(tenantId)).thenReturn(1L);
        when(aiRcaService.analyze(any())).thenReturn(new AiRcaReport(
                "Root cause", "commit1", "runbook1", 90, "P0", "remediation1"));
        when(incidentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        processor.process(message);

        verify(embeddingService).embed(expectedTextToEmbed);
        verify(embeddingService).toVectorLiteral(dummyVector);
        verify(incidentRepository).upsertIngestedIncident(
                tenantId,
                fingerprint,
                "order-service",
                rawTrace,
                sanitizedTrace,
                vectorLiteral);
    }

    @Test
    void suppressesEmbeddingOnDuplicateOccurrenceAndPreservesVectorWithNull() {
        String rawTrace = "java.lang.NullPointerException";
        String sanitizedTrace = "java.lang.NullPointerException";
        String fingerprint = "dup123hash";
        LogMessage message = new LogMessage(tenantId, "auth-service", rawTrace, "corr-2", Instant.now());

        when(sanitizer.sanitize(rawTrace)).thenReturn(sanitizedTrace);
        when(deduplicatorService.fingerprint(rawTrace)).thenReturn(fingerprint);
        // occurrence count is 2 (duplicate)
        when(deduplicatorService.record(tenantId.toString(), fingerprint))
                .thenReturn(new RedisDeduplicatorService.DeduplicationResult(fingerprint, 2, 1, 2));

        Incident existingIncident = new Incident();
        existingIncident.setId(UUID.randomUUID());
        existingIncident.setTenantId(tenantId);
        existingIncident.setFingerprintHash(fingerprint);
        existingIncident.setServiceName("auth-service");
        existingIncident.setRawStackTrace(rawTrace);
        existingIncident.setSanitizedStackTrace(sanitizedTrace);
        existingIncident.setOccurrenceCount(2);
        existingIncident.setStatus(IncidentStatus.OPEN);
        existingIncident.setAiRcaJson(objectMapper.createObjectNode().put("severity", "P1"));
        existingIncident.setFirstSeen(Instant.now());
        existingIncident.setLastSeen(Instant.now());

        when(incidentRepository.findByTenantIdAndFingerprintHash(tenantId, fingerprint))
                .thenReturn(Optional.of(existingIncident));
        when(incidentRepository.countByTenantId(tenantId)).thenReturn(1L);

        processor.process(message);

        // Verify embedding service was NOT called for duplicate
        verify(embeddingService, never()).embed(any());
        verify(embeddingService, never()).toVectorLiteral(any());

        // Verify upsert was called with null embedding (triggering COALESCE in SQL)
        verify(incidentRepository).upsertIngestedIncident(
                tenantId,
                fingerprint,
                "auth-service",
                rawTrace,
                sanitizedTrace,
                null);
    }

    @Test
    void propagatesExceptionWhenEmbeddingFailsOnFirstOccurrence() {
        String rawTrace = "java.lang.OutOfMemoryError";
        String sanitizedTrace = "java.lang.OutOfMemoryError";
        String fingerprint = "oom123";
        LogMessage message = new LogMessage(tenantId, "cache-service", rawTrace, "corr-3", Instant.now());

        when(sanitizer.sanitize(rawTrace)).thenReturn(sanitizedTrace);
        when(deduplicatorService.fingerprint(rawTrace)).thenReturn(fingerprint);
        when(deduplicatorService.record(tenantId.toString(), fingerprint))
                .thenReturn(new RedisDeduplicatorService.DeduplicationResult(fingerprint, 1, 0, 1));

        when(embeddingService.embed(any()))
                .thenThrow(new IllegalStateException("Gemini embedding API error: HTTP 503"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> processor.process(message));

        assertTrue(ex.getMessage().contains("Gemini embedding API error"));
        verify(incidentRepository, never()).upsertIngestedIncident(any(), any(), any(), any(), any(), any());
    }
}
