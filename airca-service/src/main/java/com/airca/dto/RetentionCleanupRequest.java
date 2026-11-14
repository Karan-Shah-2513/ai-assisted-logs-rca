package com.airca.dto;

import jakarta.validation.constraints.Min;

public record RetentionCleanupRequest(
        @Min(value = 0, message = "incidentRetentionDays must be non-negative")
        Integer incidentRetentionDays,

        @Min(value = 0, message = "runbookChunkRetentionDays must be non-negative")
        Integer runbookChunkRetentionDays
) {}
