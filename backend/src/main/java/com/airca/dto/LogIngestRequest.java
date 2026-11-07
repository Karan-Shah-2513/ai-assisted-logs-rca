package com.airca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LogIngestRequest(
        @NotBlank @Size(max = 200) String serviceName,
        @NotBlank @Size(max = 100_000) String stackTrace,
        @Size(max = 200) String correlationId) {
}
