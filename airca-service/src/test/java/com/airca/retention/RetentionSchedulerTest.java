package com.airca.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.airca.dto.RetentionCleanupResponse;
import com.airca.model.Tenant;
import com.airca.multitenancy.TenantContext;
import com.airca.repository.TenantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetentionSchedulerTest {

    private TenantRepository tenantRepository;
    private RetentionService retentionService;
    private RetentionProperties properties;
    private RetentionScheduler scheduler;

    private final UUID tenant1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID tenant2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        retentionService = mock(RetentionService.class);
        properties = new RetentionProperties();
        properties.setEnabled(true);

        scheduler = new RetentionScheduler(tenantRepository, retentionService, properties);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Safely scopes TenantContext for each tenant and restores context when no prior context existed")
    void iteratesTenantsAndSafelyScopesTenantContext() {
        Tenant t1 = new Tenant();
        t1.setId(tenant1);
        t1.setName("AlphaFin");

        Tenant t2 = new Tenant();
        t2.setId(tenant2);
        t2.setName("BetaPay");

        when(tenantRepository.findAll()).thenReturn(List.of(t1, t2));

        List<UUID> observedContextTenants = new ArrayList<>();
        when(retentionService.cleanupTenant(any(UUID.class))).thenAnswer(invocation -> {
            UUID target = invocation.getArgument(0);
            UUID current = TenantContext.getTenantId().orElse(null);
            observedContextTenants.add(current);
            return new RetentionCleanupResponse(target, 5, 10);
        });

        RetentionJobSummary summary = scheduler.runScheduledCleanup();

        // Verify each tenant was invoked with TenantContext set to that tenant
        assertEquals(List.of(tenant1, tenant2), observedContextTenants);
        assertEquals(2, summary.totalTenants());
        assertEquals(10, summary.totalDeletedIncidents());
        assertEquals(20, summary.totalDeletedRunbookChunks());
        assertEquals(2, summary.tenantResults().size());
        assertTrue(summary.errors().isEmpty());

        // Context must be cleanly cleared after completion
        assertTrue(TenantContext.getTenantId().isEmpty(),
                "TenantContext must be cleared after scheduled cleanup completes");
    }

    @Test
    @DisplayName("Restores pre-existing TenantContext when scheduled job was invoked with active context")
    void restoresPriorTenantContextWhenPresent() {
        UUID priorTenant = UUID.fromString("99999999-9999-9999-9999-999999999999");
        TenantContext.setTenantId(priorTenant);

        Tenant t1 = new Tenant();
        t1.setId(tenant1);
        t1.setName("AlphaFin");

        when(tenantRepository.findAll()).thenReturn(List.of(t1));
        when(retentionService.cleanupTenant(tenant1))
                .thenReturn(new RetentionCleanupResponse(tenant1, 1, 2));

        scheduler.runScheduledCleanup();

        // Must restore prior tenant
        assertEquals(priorTenant, TenantContext.getTenantId().orElse(null),
                "Scheduled job must restore pre-existing TenantContext in finally block");
    }

    @Test
    @DisplayName("Handles errors per tenant, finishes remaining tenants, and throws RetentionCleanupException")
    void doesNotSilentlySwallowErrorsAndContinuesAcrossTenants() {
        Tenant t1 = new Tenant();
        t1.setId(tenant1);
        Tenant t2 = new Tenant();
        t2.setId(tenant2);

        when(tenantRepository.findAll()).thenReturn(List.of(t1, t2));
        when(retentionService.cleanupTenant(tenant1))
                .thenThrow(new IllegalStateException("Failed connecting to partition"));
        when(retentionService.cleanupTenant(tenant2))
                .thenReturn(new RetentionCleanupResponse(tenant2, 3, 4));

        RetentionCleanupException ex = assertThrows(RetentionCleanupException.class,
                () -> scheduler.runScheduledCleanup());

        // Verify errors are not swallowed
        assertTrue(ex.getMessage().contains("Scheduled retention cleanup encountered errors"));
        assertTrue(ex.getTenantErrors().containsKey(tenant1));
        assertEquals("Failed connecting to partition", ex.getTenantErrors().get(tenant1));

        // Verify tenant2 was still cleaned up despite tenant1 failing
        verify(retentionService).cleanupTenant(tenant2);

        // Verify context is restored/cleared despite error
        assertTrue(TenantContext.getTenantId().isEmpty());
    }

    @Test
    @DisplayName("Skips execution when scheduled retention cleanup is disabled")
    void skipsExecutionWhenDisabled() {
        properties.setEnabled(false);

        RetentionJobSummary summary = scheduler.runScheduledCleanup();

        assertEquals(0, summary.totalTenants());
        assertEquals(0, summary.totalDeletedIncidents());
        verify(tenantRepository, never()).findAll();
        verify(retentionService, never()).cleanupTenant(any());
    }
}
