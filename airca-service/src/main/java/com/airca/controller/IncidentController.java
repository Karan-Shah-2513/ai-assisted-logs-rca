package com.airca.controller;

import com.airca.ai.AiRcaService;
import com.airca.dto.IncidentDto;
import com.airca.dto.ResolveIncidentRequest;
import com.airca.dto.TelemetryDto;
import com.airca.model.Incident;
import com.airca.model.IncidentStatus;
import com.airca.model.RunbookChunk;
import com.airca.multitenancy.TenantContext;
import com.airca.rag.VectorRetrieverService;
import com.airca.repository.IncidentRepository;
import com.airca.repository.TenantRepository;
import com.airca.websocket.StompBroadcasterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class IncidentController {

    private final IncidentRepository incidentRepository;
    private final TenantRepository tenantRepository;
    private final TelemetryService telemetryService;
    private final AiRcaService aiRcaService;
    private final ObjectMapper objectMapper;
    private final VectorRetrieverService vectorRetrieverService;
    private final StompBroadcasterService broadcasterService;

    @Autowired
    public IncidentController(
            IncidentRepository incidentRepository,
            TenantRepository tenantRepository,
            TelemetryService telemetryService,
            @Autowired(required = false) AiRcaService aiRcaService,
            ObjectMapper objectMapper,
            VectorRetrieverService vectorRetrieverService,
            StompBroadcasterService broadcasterService) {
        this.incidentRepository = incidentRepository;
        this.tenantRepository = tenantRepository;
        this.telemetryService = telemetryService;
        this.aiRcaService = aiRcaService;
        this.objectMapper = objectMapper;
        this.vectorRetrieverService = vectorRetrieverService;
        this.broadcasterService = broadcasterService;
    }

    public IncidentController(
            IncidentRepository incidentRepository,
            TenantRepository tenantRepository,
            TelemetryService telemetryService,
            ObjectMapper objectMapper,
            VectorRetrieverService vectorRetrieverService,
            StompBroadcasterService broadcasterService) {
        this(incidentRepository, tenantRepository, telemetryService, null, objectMapper, vectorRetrieverService, broadcasterService);
    }

    @GetMapping("/incidents")
    @Transactional(readOnly = true)
    public List<IncidentDto> getIncidents() {
        UUID tenantId = currentTenant();
        return incidentRepository.findAllByTenantIdOrderByLastSeenDesc(tenantId)
                .stream()
                .map(IncidentController::toDto)
                .toList();
    }

    @GetMapping("/incidents/{id}")
    @Transactional(readOnly = true)
    public IncidentDto getIncident(@PathVariable UUID id) {
        return toDto(findIncident(id, currentTenant()));
    }

    @PostMapping("/incidents/{id}/resolve")
    @Transactional
    public IncidentDto resolveIncident(
            @PathVariable UUID id,
            @Valid @RequestBody ResolveIncidentRequest request) {
        UUID tenantId = currentTenant();
        Incident incident = findIncident(id, tenantId);
        incident.setStatus(IncidentStatus.RESOLVED);
        incident = incidentRepository.save(incident);

        String resolution = "Resolved incident for service " + incident.getServiceName()
                + "\nVerified root cause: " + request.verifiedRootCause()
                + "\nVerified fix command or PR: " + request.verifiedFixCommandOrPR()
                + "\nResolved by: " + request.resolvedBy()
                + "\nSanitized failure signature:\n" + incident.getSanitizedStackTrace();

        vectorRetrieverService.indexResolvedIncident(
                tenantId,
                incident.getId(),
                "Resolved incident " + incident.getServiceName() + " " + incident.getId(),
                resolution,
                RunbookChunk.SOURCE_HUMAN_VERIFIED_INCIDENT);

        IncidentDto result = toDto(incident);
        publishAfterCommit(result);
        return result;
    }

    @GetMapping("/telemetry")
    @Transactional(readOnly = true)
    public TelemetryDto getTelemetry() {
        return telemetryService.getTelemetry(currentTenant());
    }

    @GetMapping("/tenants")
    @Transactional(readOnly = true)
    public List<TenantSummary> getTenants() {
        return tenantRepository.findAll().stream()
                .map(tenant -> new TenantSummary(tenant.getId(), tenant.getName()))
                .toList();
    }

    private Incident findIncident(UUID incidentId, UUID tenantId) {
        return incidentRepository.findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
    }

    private static UUID currentTenant() {
        return TenantContext.getTenantId()
                .orElseThrow(() -> new IllegalStateException("Tenant context is not set"));
    }

    private void publishAfterCommit(IncidentDto incident) {
        Runnable publish = () -> broadcasterService.broadcastIncident(incident);
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

    public record TenantSummary(UUID id, String name) {
    }
}
