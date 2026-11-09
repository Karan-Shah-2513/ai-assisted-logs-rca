package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.UUID;

public record RetentionCleanupResponse(
        UUID tenantId,
        long deletedIncidents,
        long deletedRunbookChunks,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant incidentCutoff,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant runbookChunkCutoff
) {
    public RetentionCleanupResponse(UUID tenantId, long deletedIncidents, long deletedRunbookChunks) {
        this(tenantId, deletedIncidents, deletedRunbookChunks, null, null);
    }

    public long getDeletedIncidents() {
        return deletedIncidents;
    }

    public long getDeletedRunbookChunks() {
        return deletedRunbookChunks;
    }
}
