package com.airca.ingestion;

import com.airca.ai.AiRcaService;
import com.airca.ai.TextEmbeddingService;
import com.airca.controller.TelemetryService;
import com.airca.deduplication.RedisDeduplicatorService;
import com.airca.dto.IncidentDto;
import com.airca.dto.TelemetryDto;
import com.airca.model.Incident;
import com.airca.repository.IncidentRepository;
import com.airca.websocket.StompBroadcasterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class LogIngestionProcessor {

    private final RedisDeduplicatorService deduplicatorService;
    private final LogSanitizer sanitizer;
    private final IncidentRepository incidentRepository;
    private final AiRcaService aiRcaService;
    private final ObjectMapper objectMapper;
    private final StompBroadcasterService broadcasterService;
    private final TextEmbeddingService embeddingService;

    public LogIngestionProcessor(
            RedisDeduplicatorService deduplicatorService,
            LogSanitizer sanitizer,
            IncidentRepository incidentRepository,
            AiRcaService aiRcaService,
            ObjectMapper objectMapper,
            StompBroadcasterService broadcasterService,
            TextEmbeddingService embeddingService) {
        this.deduplicatorService = deduplicatorService;
        this.sanitizer = sanitizer;
        this.incidentRepository = incidentRepository;
        this.aiRcaService = aiRcaService;
        this.objectMapper = objectMapper;
        this.broadcasterService = broadcasterService;
        this.embeddingService = embeddingService;
    }

    public LogIngestionProcessor(
            RedisDeduplicatorService deduplicatorService,
            LogSanitizer sanitizer,
            IncidentRepository incidentRepository,
            AiRcaService aiRcaService,
            ObjectMapper objectMapper,
            StompBroadcasterService broadcasterService) {
        this(deduplicatorService, sanitizer, incidentRepository, aiRcaService, objectMapper, broadcasterService, null);
    }

    @Transactional
    public void process(LogMessage message) {
        UUID tenantId = message.tenantId();
        String sanitizedStackTrace = sanitizer.sanitize(message.stackTrace());
        String fingerprint = deduplicatorService.fingerprint(message.stackTrace());
        RedisDeduplicatorService.DeduplicationResult deduplication =
                deduplicatorService.record(tenantId.toString(), fingerprint);

        String vectorLiteral = null;
        if (embeddingService != null && deduplication.occurrenceCount() == 1) {
            String textToEmbed = message.serviceName() + "\n" + sanitizedStackTrace;
            List<Double> embedding = embeddingService.embed(textToEmbed);
            vectorLiteral = embeddingService.toVectorLiteral(embedding);
        }

        incidentRepository.upsertIngestedIncident(
                tenantId,
                fingerprint,
                message.serviceName(),
                message.stackTrace(),
                sanitizedStackTrace,
                vectorLiteral);

        Incident incident = incidentRepository.findByTenantIdAndFingerprintHash(tenantId, fingerprint)
                .orElseThrow(() -> new IllegalStateException("Ingested incident was not persisted"));
        if (incident.getAiRcaJson() == null) {
            incident.setAiRcaJson(objectMapper.valueToTree(aiRcaService.analyze(incident)));
            incident = incidentRepository.save(incident);
        }

        IncidentDto incidentDto = toDto(incident);
        TelemetryDto telemetry = TelemetryService.create(
                tenantId,
                deduplication.totalLogs(),
                deduplication.deduplicatedLogs(),
                incidentRepository.countByTenantId(tenantId));
        publishAfterCommit(incidentDto, telemetry);
    }

    private void publishAfterCommit(IncidentDto incident, TelemetryDto telemetry) {
        Runnable publish = () -> {
            broadcasterService.broadcastIncident(incident);
            broadcasterService.broadcastTelemetry(telemetry);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private static IncidentDto toDto(Incident incident) {
        return new IncidentDto(
                incident.getId(),
                incident.getTenantId(),
                incident.getFingerprintHash(),
                incident.getServiceName(),
                incident.getRawStackTrace(),
                incident.getSanitizedStackTrace(),
                incident.getOccurrenceCount(),
                incident.getStatus(),
                incident.getAiRcaJson(),
                incident.getFirstSeen(),
                incident.getLastSeen());
    }
}
