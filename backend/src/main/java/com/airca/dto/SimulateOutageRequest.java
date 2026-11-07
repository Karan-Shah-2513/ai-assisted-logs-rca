package com.airca.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SimulateOutageRequest(
        @NotBlank String scenario,
        @NotNull UUID tenantId,
        @Min(1) @Max(100_000) int logCount) {
}
