package com.airca.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.airca.dto.RetentionCleanupResponse;
import com.airca.model.IncidentStatus;
import com.airca.model.RunbookChunk;
import com.airca.multitenancy.TenantContext;
import com.airca.repository.IncidentRepository;
import com.airca.repository.RunbookChunkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RetentionServiceTest {

    private IncidentRepository incidentRepository;
    private RunbookChunkRepository runbookChunkRepository;
    private RetentionProperties properties;
    private RetentionService retentionService;

    private final Instant fixedNow = Instant.parse("2026-11-09T12:00:00Z");
    private final Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);
    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        runbookChunkRepository = mock(RunbookChunkRepository.class);
        properties = new RetentionProperties();
        properties.setIncidentRetentionDays(30);
        properties.setRunbookChunkRetentionDays(180);

        retentionService = new RetentionService(
                incidentRepository,
                runbookChunkRepository,
                properties,
                fixedClock);

        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Deletes only resolved incidents older than configured default 30 days retention")
    void deletesOnlyResolvedIncidentsOlderThanCutoff() {
        Instant expectedIncidentCutoff = fixedNow.minus(Duration.ofDays(30));
        Instant expectedChunkCutoff = fixedNow.minus(Duration.ofDays(180));

        when(incidentRepository.deleteByTenantIdAndStatusAndLastSeenBefore(
                tenantId, IncidentStatus.RESOLVED, expectedIncidentCutoff)).thenReturn(4);
        when(runbookChunkRepository.deleteByTenantIdAndSourceAndCreatedAtBefore(
                tenantId, RunbookChunk.SOURCE_RUNBOOK, expectedChunkCutoff)).thenReturn(7);

        RetentionCleanupResponse result = retentionService.cleanupTenant(tenantId);

        assertEquals(tenantId, result.tenantId());
        assertEquals(4, result.deletedIncidents());
        assertEquals(7, result.deletedRunbookChunks());
        assertEquals(expectedIncidentCutoff, result.incidentCutoff());
        assertEquals(expectedChunkCutoff, result.runbookChunkCutoff());

        // Verify status is strictly RESOLVED - OPEN and INVESTIGATING incidents are NEVER targeted
        verify(incidentRepository).deleteByTenantIdAndStatusAndLastSeenBefore(
                tenantId, IncidentStatus.RESOLVED, expectedIncidentCutoff);
        verify(incidentRepository, never()).deleteByTenantIdAndStatusAndLastSeenBefore(
                eq(tenantId), eq(IncidentStatus.OPEN), any());
        verify(incidentRepository, never()).deleteByTenantIdAndStatusAndLastSeenBefore(
                eq(tenantId), eq(IncidentStatus.INVESTIGATING), any());
    }

    @Test
    @DisplayName("Deletes only RUNBOOK chunks and strictly preserves HUMAN_VERIFIED_INCIDENT chunks")
    void strictlyPreservesHumanVerifiedIncidentChunks() {
        Instant expectedChunkCutoff = fixedNow.minus(Duration.ofDays(180));

        when(incidentRepository.deleteByTenantIdAndStatusAndLastSeenBefore(any(), any(), any())).thenReturn(0);
        when(runbookChunkRepository.deleteByTenantIdAndSourceAndCreatedAtBefore(
                eq(tenantId), eq(RunbookChunk.SOURCE_RUNBOOK), eq(expectedChunkCutoff))).thenReturn(5);

        retentionService.cleanupTenant(tenantId);

        // Verify delete query targets ONLY "RUNBOOK" source
        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        verify(runbookChunkRepository).deleteByTenantIdAndSourceAndCreatedAtBefore(
                eq(tenantId), sourceCaptor.capture(), eq(expectedChunkCutoff));

        assertEquals(RunbookChunk.SOURCE_RUNBOOK, sourceCaptor.getValue());

        // Verify HUMAN_VERIFIED_INCIDENT chunks are never passed to deletion
        verify(runbookChunkRepository, never()).deleteByTenantIdAndSourceAndCreatedAtBefore(
                eq(tenantId), eq(RunbookChunk.SOURCE_HUMAN_VERIFIED_INCIDENT), any());
    }

    @Test
    @DisplayName("Preserves HUMAN_VERIFIED_INCIDENT chunks even when linked resolved incidents are purged")
    void preservesHumanVerifiedChunksEvenWhenLinkedIncidentsArePurged() {
        UUID linkedIncidentId = UUID.randomUUID();
        Instant ancient = fixedNow.minus(Duration.ofDays(365));

        // Create an in-memory representation of a human-verified chunk linked to the incident
        RunbookChunk humanChunk = new RunbookChunk();
        humanChunk.setId(UUID.randomUUID());
        humanChunk.setTenantId(tenantId);
        humanChunk.setSource(RunbookChunk.SOURCE_HUMAN_VERIFIED_INCIDENT);
        humanChunk.setSourceIncidentId(linkedIncidentId);
        humanChunk.setRunbookName("Incident RCA Resolution");
        humanChunk.setChunkText("Verified resolution details");

        when(incidentRepository.deleteByTenantIdAndStatusAndLastSeenBefore(eq(tenantId), eq(IncidentStatus.RESOLVED), any()))
                .thenReturn(1);
        when(runbookChunkRepository.deleteByTenantIdAndSourceAndCreatedAtBefore(eq(tenantId), eq(RunbookChunk.SOURCE_RUNBOOK), any()))
                .thenReturn(0);

        RetentionCleanupResponse response = retentionService.cleanupTenant(tenantId);

        assertEquals(1, response.deletedIncidents());
        assertEquals(0, response.deletedRunbookChunks());

        // Migration 005 sets source_incident_id to NULL on DB cascade, so chunk entity survives
        humanChunk.setSourceIncidentId(null);
        assertEquals(RunbookChunk.SOURCE_HUMAN_VERIFIED_INCIDENT, humanChunk.getSource());
        assertEquals(tenantId, humanChunk.getTenantId());

        // Verify that the repository never received a delete command for HUMAN_VERIFIED_INCIDENT
        verify(runbookChunkRepository, never()).deleteByTenantIdAndSourceAndCreatedAtBefore(
                eq(tenantId), eq(RunbookChunk.SOURCE_HUMAN_VERIFIED_INCIDENT), any());
    }

    @Test
    @DisplayName("Applies custom retention days when explicitly specified")
    void appliesCustomRetentionDays() {
        int customIncidentDays = 14;
        int customChunkDays = 60;
        Instant expectedIncidentCutoff = fixedNow.minus(Duration.ofDays(customIncidentDays));
        Instant expectedChunkCutoff = fixedNow.minus(Duration.ofDays(customChunkDays));

        when(incidentRepository.deleteByTenantIdAndStatusAndLastSeenBefore(
                tenantId, IncidentStatus.RESOLVED, expectedIncidentCutoff)).thenReturn(2);
        when(runbookChunkRepository.deleteByTenantIdAndSourceAndCreatedAtBefore(
                tenantId, RunbookChunk.SOURCE_RUNBOOK, expectedChunkCutoff)).thenReturn(3);

        RetentionCleanupResponse result = retentionService.cleanupTenant(tenantId, customIncidentDays, customChunkDays);

        assertEquals(2, result.deletedIncidents());
        assertEquals(3, result.deletedRunbookChunks());
        assertEquals(expectedIncidentCutoff, result.incidentCutoff());
        assertEquals(expectedChunkCutoff, result.runbookChunkCutoff());
    }

    @Test
    @DisplayName("Rejects negative retention days with IllegalArgumentException")
    void rejectsNegativeRetentionDays() {
        assertThrows(IllegalArgumentException.class,
                () -> retentionService.cleanupTenant(tenantId, -1, 30));
        assertThrows(IllegalArgumentException.class,
                () -> retentionService.cleanupTenant(tenantId, 30, -5));
    }

    @Test
    @DisplayName("Rejects null tenantId with IllegalArgumentException")
    void rejectsNullTenantId() {
        assertThrows(IllegalArgumentException.class,
                () -> retentionService.cleanupTenant(null));
    }

    @Test
    @DisplayName("Verifies tenant context match when TenantContext is active")
    void throwsWhenTenantContextDoesNotMatchTargetTenant() {
        UUID activeTenant = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TenantContext.setTenantId(activeTenant);

        assertThrows(IllegalStateException.class,
                () -> retentionService.cleanupTenant(tenantId));
    }

    @Test
    @DisplayName("Succeeds when TenantContext matches the target tenantId")
    void succeedsWhenTenantContextMatches() {
        TenantContext.setTenantId(tenantId);
        when(incidentRepository.deleteByTenantIdAndStatusAndLastSeenBefore(any(), any(), any())).thenReturn(1);
        when(runbookChunkRepository.deleteByTenantIdAndSourceAndCreatedAtBefore(any(), any(), any())).thenReturn(1);

        RetentionCleanupResponse result = retentionService.cleanupTenant(tenantId);
        assertNotNull(result);
        assertEquals(1, result.deletedIncidents());
    }

    @Test
    @DisplayName("Does not swallow database repository exceptions")
    void propagatesRepositoryExceptions() {
        when(incidentRepository.deleteByTenantIdAndStatusAndLastSeenBefore(any(), any(), any()))
                .thenThrow(new RuntimeException("Database connection timed out"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> retentionService.cleanupTenant(tenantId));
        assertEquals("Database connection timed out", ex.getMessage());
    }
}
