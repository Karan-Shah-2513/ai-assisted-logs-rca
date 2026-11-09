package com.airca.controller;

import com.airca.deduplication.RedisDeduplicatorService;
import com.airca.dto.TelemetryDto;
import com.airca.repository.IncidentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TelemetryService {

    private static final double COST_PER_DEDUPLICATED_LOG_USD = 0.015;

    private final RedisDeduplicatorService deduplicatorService;
    private final IncidentRepository incidentRepository;

    public TelemetryService(
            RedisDeduplicatorService deduplicatorService,
            IncidentRepository incidentRepository) {
        this.deduplicatorService = deduplicatorService;
        this.incidentRepository = incidentRepository;
    }

    public TelemetryDto getTelemetry(UUID tenantId) {
        RedisDeduplicatorService.DeduplicationStats stats =
                deduplicatorService.getStats(tenantId.toString());
        return create(tenantId, stats.totalLogs(), stats.deduplicatedLogs(),
                incidentRepository.countByTenantId(tenantId));
    }

    public static TelemetryDto create(
            UUID tenantId, long totalLogs, long deduplicatedLogs, long uniqueIncidents) {
        double ratio = totalLogs == 0 ? 0.0 : (double) deduplicatedLogs * 100.0 / totalLogs;
        return new TelemetryDto(
                tenantId,
                totalLogs,
                deduplicatedLogs,
                uniqueIncidents,
                ratio,
                deduplicatedLogs * COST_PER_DEDUPLICATED_LOG_USD);
    }
}
