package com.airca.controller;

import com.airca.dto.RetentionCleanupRequest;
import com.airca.dto.RetentionCleanupResponse;
import com.airca.multitenancy.TenantContext;
import com.airca.retention.RetentionProperties;
import com.airca.retention.RetentionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class AdminRetentionController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminRetentionController.class);

    private final RetentionService retentionService;
    private final RetentionProperties properties;

    public AdminRetentionController(
            RetentionService retentionService,
            RetentionProperties properties) {
        this.retentionService = retentionService;
        this.properties = properties;
    }

    @PostMapping(path = {"/admin/retention/cleanup", "/admin/cleanup", "/retention/cleanup"})
    public RetentionCleanupResponse manualRetentionCleanup(
            @Valid @RequestBody(required = false) RetentionCleanupRequest request) {
        UUID tenantId = TenantContext.getTenantId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant context is not set"));

        int incidentDays = (request != null && request.incidentRetentionDays() != null)
                ? request.incidentRetentionDays()
                : properties.getIncidentRetentionDays();

        int chunkDays = (request != null && request.runbookChunkRetentionDays() != null)
                ? request.runbookChunkRetentionDays()
                : properties.getRunbookChunkRetentionDays();

        if (incidentDays < 0 || chunkDays < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Retention days cannot be negative");
        }

        LOGGER.info("Manual retention cleanup requested for current tenant {} (incidentDays={}, chunkDays={})",
                tenantId, incidentDays, chunkDays);

        RetentionCleanupResponse response = retentionService.cleanupTenant(tenantId, incidentDays, chunkDays);

        LOGGER.info("Manual retention cleanup completed for current tenant {}: deleted {} incidents, deleted {} runbook chunks",
                tenantId, response.deletedIncidents(), response.deletedRunbookChunks());

        return response;
    }
}
