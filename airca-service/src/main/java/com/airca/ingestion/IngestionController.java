package com.airca.ingestion;

import com.airca.deduplication.RedisDeduplicatorService;
import com.airca.dto.IngestResponse;
import com.airca.dto.LogIngestRequest;
import com.airca.multitenancy.TenantContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/logs")
public class IngestionController {

    private final LogKafkaProducer kafkaProducer;
    private final RedisDeduplicatorService deduplicatorService;

    public IngestionController(
            LogKafkaProducer kafkaProducer, RedisDeduplicatorService deduplicatorService) {
        this.kafkaProducer = kafkaProducer;
        this.deduplicatorService = deduplicatorService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestResponse ingest(@Valid @RequestBody LogIngestRequest request) {
        UUID tenantId = TenantContext.getTenantId()
                .orElseThrow(() -> new IllegalStateException("Tenant context is not set"));
        String correlationId = request.correlationId() == null || request.correlationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.correlationId().trim();
        String fingerprint = deduplicatorService.fingerprint(request.stackTrace());
        kafkaProducer.publish(tenantId, request.serviceName(), request.stackTrace(), correlationId);
        return new IngestResponse("QUEUED", fingerprint, correlationId);
    }
}
