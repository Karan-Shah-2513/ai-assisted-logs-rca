package com.airca.retention;

import com.airca.dto.RetentionCleanupResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RetentionJobSummary(
        int totalTenants,
        long totalDeletedIncidents,
        long totalDeletedRunbookChunks,
        List<RetentionCleanupResponse> tenantResults,
        Map<UUID, String> errors
) {}
