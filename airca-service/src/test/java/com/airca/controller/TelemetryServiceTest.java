package com.airca.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.airca.deduplication.RedisDeduplicatorService;
import com.airca.dto.TelemetryDto;
import com.airca.repository.IncidentRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelemetryServiceTest {

    private RedisDeduplicatorService deduplicatorService;
    private IncidentRepository incidentRepository;
    private TelemetryService telemetryService;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        deduplicatorService = mock(RedisDeduplicatorService.class);
        incidentRepository = mock(IncidentRepository.class);
        telemetryService = new TelemetryService(deduplicatorService, incidentRepository);
    }

    @Test
    @DisplayName("Distinguish total decision evaluations from noise-only decision_gated_logs")
    void testDistinguishTotalDecisionsFromNoiseGatedLogs() {
        when(deduplicatorService.getStats(tenantId.toString()))
                .thenReturn(new RedisDeduplicatorService.DeduplicationStats(1000L, 800L));
        when(incidentRepository.countByTenantId(tenantId)).thenReturn(200L);

        // 12 logs were noise-gated and skipped
        when(deduplicatorService.getDecisionGatedLogs(tenantId.toString())).thenReturn(12L);
        // 55 incidents evaluated overall
        when(incidentRepository.countDecisionsByTenantId(tenantId)).thenReturn(55L);
        when(incidentRepository.findAverageDecisionLatencyMsByTenantId(tenantId)).thenReturn(48.2);

        TelemetryDto telemetry = telemetryService.getTelemetry(tenantId);

        assertEquals(1000L, telemetry.totalLogs());
        assertEquals(800L, telemetry.deduplicatedLogs());
        assertEquals(200L, telemetry.uniqueIncidents());
        assertEquals(80.0, telemetry.deduplicationRatio(), 0.001);
        assertEquals(12.0, telemetry.estimatedCostSavedUsd(), 0.001);

        // Crucial: noise-gated count (12) must NOT be mislabeled as total decisions (55)
        assertEquals(12L, telemetry.decisionGatedLogs(), "Must report noise-only gated logs accurately");
        assertEquals(55L, telemetry.totalDecisions(), "Must report total decisions evaluated, not noise-only");
        assertEquals(55L, telemetry.decisionEvaluatedIncidents());
        assertEquals(48.2, telemetry.averageDecisionLatencyMs(), 0.001);
        assertEquals(48.2, telemetry.decisionLatencyMs(), 0.001);
    }

    @Test
    @DisplayName("Fallback to TTL-consistent Redis decision stats when DB count/latency is zero or empty")
    void testFallbackToRedisStatsWhenDbEmpty() {
        when(deduplicatorService.getStats(tenantId.toString()))
                .thenReturn(new RedisDeduplicatorService.DeduplicationStats(500L, 400L));
        when(incidentRepository.countByTenantId(tenantId)).thenReturn(100L);
        when(deduplicatorService.getDecisionGatedLogs(tenantId.toString())).thenReturn(5L);

        // DB returns 0 decisions and null/0.0 latency
        when(incidentRepository.countDecisionsByTenantId(tenantId)).thenReturn(0L);
        when(incidentRepository.findAverageDecisionLatencyMsByTenantId(tenantId)).thenReturn(0.0);

        // Redis has TTL-consistent stats
        when(deduplicatorService.getDecisionEvaluatedCount(tenantId.toString())).thenReturn(25L);
        when(deduplicatorService.getAverageDecisionLatencyMs(tenantId.toString())).thenReturn(72.5);

        TelemetryDto telemetry = telemetryService.getTelemetry(tenantId);

        assertEquals(5L, telemetry.decisionGatedLogs());
        assertEquals(25L, telemetry.totalDecisions(), "Must fall back to Redis decision count when DB is 0");
        assertEquals(72.5, telemetry.averageDecisionLatencyMs(), 0.001, "Must fall back to Redis latency when DB is 0");
    }

    @Test
    @DisplayName("Handle zero total logs safely without division by zero")
    void testZeroTotalLogsHandlesSafely() {
        when(deduplicatorService.getStats(tenantId.toString()))
                .thenReturn(new RedisDeduplicatorService.DeduplicationStats(0L, 0L));
        when(incidentRepository.countByTenantId(tenantId)).thenReturn(0L);
        when(deduplicatorService.getDecisionGatedLogs(tenantId.toString())).thenReturn(0L);
        when(incidentRepository.countDecisionsByTenantId(tenantId)).thenReturn(0L);
        when(deduplicatorService.getDecisionEvaluatedCount(tenantId.toString())).thenReturn(0L);
        when(incidentRepository.findAverageDecisionLatencyMsByTenantId(tenantId)).thenReturn(0.0);
        when(deduplicatorService.getAverageDecisionLatencyMs(tenantId.toString())).thenReturn(0.0);

        TelemetryDto telemetry = telemetryService.getTelemetry(tenantId);

        assertEquals(0L, telemetry.totalLogs());
        assertEquals(0.0, telemetry.deduplicationRatio());
        assertEquals(0.0, telemetry.estimatedCostSavedUsd());
        assertEquals(0L, telemetry.decisionGatedLogs());
        assertEquals(0L, telemetry.totalDecisions());
        assertEquals(0.0, telemetry.averageDecisionLatencyMs());
    }
}
