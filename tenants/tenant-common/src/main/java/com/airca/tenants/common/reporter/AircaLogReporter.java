package com.airca.tenants.common.reporter;

import com.airca.tenants.common.config.TenantAircaProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

public class AircaLogReporter {

    private static final Logger log = LoggerFactory.getLogger(AircaLogReporter.class);

    private final TenantAircaProperties properties;
    private final RestClient restClient;

    public AircaLogReporter(TenantAircaProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    public AircaLogReporter(TenantAircaProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder.build());
    }

    /**
     * Reports an error log to the Airca ingestion endpoint.
     * Serializes serviceName, stackTrace, and correlationId to the Airca POST /api/v1/logs JSON contract,
     * and sends configured X-Tenant-ID and X-API-Key headers.
     * Explicitly logs failures; does not silently drop them.
     */
    public boolean reportError(String serviceName, String stackTrace, String correlationId) {
        if (!properties.isEnabled()) {
            log.debug("Airca error log shipping is disabled by configuration");
            return false;
        }

        String effectiveServiceName = (serviceName != null && !serviceName.isBlank())
                ? serviceName.trim()
                : (properties.getServiceName() != null ? properties.getServiceName().trim() : "unknown-service");

        String effectiveCorrelationId = (correlationId != null && !correlationId.isBlank())
                ? correlationId.trim()
                : UUID.randomUUID().toString();

        String payloadStackTrace = stackTrace != null ? stackTrace : "";
        if (payloadStackTrace.length() > 95_000) {
            payloadStackTrace = payloadStackTrace.substring(0, 95_000) + "\n...[truncated]";
        }

        String aircaUrl = properties.getAircaUrl();
        String tenantId = properties.getTenantId();
        String apiKey = properties.getApiKey();

        try {
            LogIngestPayload payload = new LogIngestPayload(
                    effectiveServiceName,
                    payloadStackTrace,
                    effectiveCorrelationId
            );

            log.info("Reporting error log to Airca [endpoint={}, tenantId={}, service={}, correlationId={}]",
                    aircaUrl, tenantId, effectiveServiceName, effectiveCorrelationId);

            var requestSpec = restClient.post()
                    .uri(aircaUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload);

            if (tenantId != null && !tenantId.isBlank()) {
                requestSpec.header("X-Tenant-ID", tenantId.trim());
            }
            if (apiKey != null && !apiKey.isBlank()) {
                requestSpec.header("X-API-Key", apiKey.trim());
            }

            ResponseEntity<Void> response = requestSpec.retrieve().toBodilessEntity();

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully reported error log to Airca for correlationId={}", effectiveCorrelationId);
                return true;
            } else {
                log.error("Failed to report error log to Airca: non-2xx status {} received for correlationId={}",
                        response.getStatusCode(), effectiveCorrelationId);
                return false;
            }
        } catch (Exception ex) {
            // Explicitly log reporting failure; don't silently drop it.
            log.error("Failed to report error log to Airca [endpoint={}, tenantId={}, service={}, correlationId={}]: {}",
                    aircaUrl, tenantId, effectiveServiceName, effectiveCorrelationId, ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Alias for reportError.
     */
    public boolean shipError(String serviceName, String stackTrace, String correlationId) {
        return reportError(serviceName, stackTrace, correlationId);
    }

    public TenantAircaProperties getProperties() {
        return properties;
    }
}
