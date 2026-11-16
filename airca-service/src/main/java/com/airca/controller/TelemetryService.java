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
        long uniqueIncidents = incidentRepository.countByTenantId(tenantId);

        long decisionGatedLogs = 0L;
        try {
            decisionGatedLogs = deduplicatorService.getDecisionGatedLogs(tenantId.toString());
        } catch (Exception ignored) {
        }

        long totalDecisions = 0L;
        try {
            totalDecisions = incidentRepository.countDecisionsByTenantId(tenantId);
        } catch (Exception ignored) {
        }
        if (totalDecisions == 0L) {
            try {
                totalDecisions = deduplicatorService.getDecisionEvaluatedCount(tenantId.toString());
            } catch (Exception ignored) {
            }
        }

        double averageLatencyMs = 0.0;
        try {
            Double dbAvg = incidentRepository.findAverageDecisionLatencyMsByTenantId(tenantId);
            if (dbAvg != null && dbAvg > 0.0) {
                averageLatencyMs = dbAvg;
            }
        } catch (Exception ignored) {
        }
        if (averageLatencyMs == 0.0) {
            try {
                averageLatencyMs = deduplicatorService.getAverageDecisionLatencyMs(tenantId.toString());
            } catch (Exception ignored) {
            }
        }

        return create(
                tenantId,
                stats.totalLogs(),
                stats.deduplicatedLogs(),
                uniqueIncidents,
                decisionGatedLogs,
                totalDecisions,
                averageLatencyMs);
    }

    public static TelemetryDto create(
            UUID tenantId,
            long totalLogs,
            long deduplicatedLogs,
            long uniqueIncidents,
            long decisionGatedLogs,
            long totalDecisions,
            double averageDecisionLatencyMs) {
        double ratio = totalLogs == 0 ? 0.0 : (double) deduplicatedLogs * 100.0 / totalLogs;
        return new TelemetryDto(
                tenantId,
                totalLogs,
                deduplicatedLogs,
                uniqueIncidents,
                ratio,
                deduplicatedLogs * COST_PER_DEDUPLICATED_LOG_USD,
                decisionGatedLogs,
                totalDecisions,
                averageDecisionLatencyMs);
    }

    public static TelemetryDto create(
            UUID tenantId, long totalLogs, long deduplicatedLogs, long uniqueIncidents) {
        return create(tenantId, totalLogs, deduplicatedLogs, uniqueIncidents, 0L, 0L, 0.0);
    }
}
