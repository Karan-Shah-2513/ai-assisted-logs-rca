package com.airca.retention;

import com.airca.dto.RetentionCleanupResponse;
import com.airca.model.IncidentStatus;
import com.airca.model.RunbookChunk;
import com.airca.multitenancy.TenantContext;
import com.airca.repository.IncidentRepository;
import com.airca.repository.RunbookChunkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetentionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionService.class);

    private final IncidentRepository incidentRepository;
    private final RunbookChunkRepository runbookChunkRepository;
    private final RetentionProperties properties;
    private final Clock clock;

    @Autowired
    public RetentionService(
            IncidentRepository incidentRepository,
            RunbookChunkRepository runbookChunkRepository,
            RetentionProperties properties) {
        this(incidentRepository, runbookChunkRepository, properties, Clock.systemUTC());
    }

    public RetentionService(
            IncidentRepository incidentRepository,
            RunbookChunkRepository runbookChunkRepository,
            RetentionProperties properties,
            Clock clock) {
        this.incidentRepository = incidentRepository;
        this.runbookChunkRepository = runbookChunkRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public RetentionCleanupResponse cleanupTenant(UUID tenantId) {
        return cleanupTenant(tenantId, properties.getIncidentRetentionDays(), properties.getRunbookChunkRetentionDays());
    }

    @Transactional
    public RetentionCleanupResponse cleanupTenant(UUID tenantId, int incidentRetentionDays, int runbookChunkRetentionDays) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant ID must not be null");
        }
        if (incidentRetentionDays < 0 || runbookChunkRetentionDays < 0) {
            throw new IllegalArgumentException("Retention days cannot be negative");
        }

        // Validate that if a tenant context is active, it matches the tenant being cleaned up
        TenantContext.getTenantId().ifPresent(contextTenant -> {
            if (!contextTenant.equals(tenantId)) {
                throw new IllegalStateException("TenantContext (" + contextTenant + ") does not match target tenant (" + tenantId + ")");
            }
        });

        Instant now = Instant.now(clock);
        Instant incidentCutoff = now.minus(Duration.ofDays(incidentRetentionDays));
        Instant chunkCutoff = now.minus(Duration.ofDays(runbookChunkRetentionDays));

        LOGGER.info("Executing retention cleanup for tenant {}: incidentCutoff={}, chunkCutoff={}",
                tenantId, incidentCutoff, chunkCutoff);

        // Delete resolved incidents older than cutoff
        int deletedIncidents = incidentRepository.deleteByTenantIdAndStatusAndLastSeenBefore(
                tenantId,
                IncidentStatus.RESOLVED,
                incidentCutoff);

        // Delete ordinary RUNBOOK chunks older than cutoff.
        // Chunks with SOURCE_HUMAN_VERIFIED_INCIDENT are strictly preserved.
        int deletedChunks = runbookChunkRepository.deleteByTenantIdAndSourceAndCreatedAtBefore(
                tenantId,
                RunbookChunk.SOURCE_RUNBOOK,
                chunkCutoff);

        LOGGER.info("Retention cleanup completed for tenant {}: deleted {} resolved incidents, deleted {} stale runbook chunks",
                tenantId, deletedIncidents, deletedChunks);

        return new RetentionCleanupResponse(tenantId, deletedIncidents, deletedChunks, incidentCutoff, chunkCutoff);
    }
}
