package com.airca.retention;

import com.airca.dto.RetentionCleanupResponse;
import com.airca.model.Tenant;
import com.airca.multitenancy.TenantContext;
import com.airca.repository.TenantRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionScheduler.class);

    private final TenantRepository tenantRepository;
    private final RetentionService retentionService;
    private final RetentionProperties properties;

    public RetentionScheduler(
            TenantRepository tenantRepository,
            RetentionService retentionService,
            RetentionProperties properties) {
        this.tenantRepository = tenantRepository;
        this.retentionService = retentionService;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.retention.cleanup-cron:0 0 3 * * *}")
    public RetentionJobSummary runScheduledCleanup() {
        if (!properties.isEnabled()) {
            LOGGER.info("Scheduled retention cleanup is disabled by configuration");
            return new RetentionJobSummary(0, 0, 0, List.of(), Map.of());
        }

        LOGGER.info("Starting scheduled retention cleanup across all tenants");
        List<Tenant> tenants = tenantRepository.findAll();
        LOGGER.info("Found {} tenants for scheduled retention cleanup", tenants.size());

        long totalDeletedIncidents = 0;
        long totalDeletedChunks = 0;
        List<RetentionCleanupResponse> tenantResults = new ArrayList<>();
        Map<UUID, String> errors = new LinkedHashMap<>();

        for (Tenant tenant : tenants) {
            UUID tenantId = tenant.getId();
            UUID previousTenant = TenantContext.getTenantId().orElse(null);
            try {
                TenantContext.setTenantId(tenantId);
                RetentionCleanupResponse result = retentionService.cleanupTenant(tenantId);
                totalDeletedIncidents += result.deletedIncidents();
                totalDeletedChunks += result.deletedRunbookChunks();
                tenantResults.add(result);
            } catch (Exception ex) {
                LOGGER.error("Failed retention cleanup for tenant {}: {}", tenantId, ex.getMessage(), ex);
                errors.put(tenantId, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            } finally {
                if (previousTenant != null) {
                    TenantContext.setTenantId(previousTenant);
                } else {
                    TenantContext.clear();
                }
            }
        }

        LOGGER.info("Scheduled retention cleanup finished: {} incidents and {} runbook chunks deleted across {} tenants. Errors: {}",
                totalDeletedIncidents, totalDeletedChunks, tenants.size(), errors.size());

        if (!errors.isEmpty()) {
            throw new RetentionCleanupException(
                    "Scheduled retention cleanup encountered errors for " + errors.size() + " tenant(s): " + errors.keySet(),
                    errors);
        }

        return new RetentionJobSummary(
                tenants.size(),
                totalDeletedIncidents,
                totalDeletedChunks,
                tenantResults,
                errors);
    }
}
