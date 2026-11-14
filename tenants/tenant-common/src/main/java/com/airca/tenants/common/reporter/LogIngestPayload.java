package com.airca.tenants.common.reporter;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LogIngestPayload(
        @JsonProperty("serviceName") String serviceName,
        @JsonProperty("stackTrace") String stackTrace,
        @JsonProperty("correlationId") String correlationId
) {
}
