package com.airca.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.airca.multitenancy.TenantContext;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DataSourceConfigTest {

    private PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbcTemplate;
    private UUID defaultTenantId;
    private DataSourceConfig dataSourceConfig;
    private ProceedingJoinPoint joinPoint;

    @BeforeEach
    void setUp() throws Throwable {
        TenantContext.clear();
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());

        jdbcTemplate = mock(JdbcTemplate.class);
        defaultTenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        dataSourceConfig = new DataSourceConfig(transactionManager, jdbcTemplate, defaultTenantId);
        joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("target-result");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void applyTenantSettingUsesContextTenantWhenPresent() throws Throwable {
        UUID customTenant = UUID.fromString("22222222-2222-2222-2222-222222222222");
        TenantContext.setTenantId(customTenant);

        Object result = dataSourceConfig.applyTenantSetting(joinPoint);

        assertEquals("target-result", result);
        verify(jdbcTemplate).queryForObject(
                eq("select set_config('app.current_tenant', ?, true)"),
                eq(String.class),
                eq(customTenant.toString()));
        verify(joinPoint).proceed();
    }

    @Test
    void applyTenantSettingFailsClosedWithEmptySettingWhenContextMissing() throws Throwable {
        // TenantContext is intentionally empty: must NOT fallback to defaultTenantId
        Object result = dataSourceConfig.applyTenantSetting(joinPoint);

        assertEquals("target-result", result);
        verify(jdbcTemplate).queryForObject(
                eq("select set_config('app.current_tenant', ?, true)"),
                eq(String.class),
                eq(""));
        verify(joinPoint).proceed();
    }

    @Test
    void applyTenantSettingReflectsContextClearAcrossCalls() throws Throwable {
        UUID tenantA = UUID.fromString("33333333-3333-3333-3333-333333333333");
        TenantContext.setTenantId(tenantA);

        dataSourceConfig.applyTenantSetting(joinPoint);
        verify(jdbcTemplate).queryForObject(
                eq("select set_config('app.current_tenant', ?, true)"),
                eq(String.class),
                eq(tenantA.toString()));

        TenantContext.clear();
        dataSourceConfig.applyTenantSetting(joinPoint);
        verify(jdbcTemplate).queryForObject(
                eq("select set_config('app.current_tenant', ?, true)"),
                eq(String.class),
                eq(""));
    }

    @Test
    void applyTenantSettingPropagatesTargetException() throws Throwable {
        UUID tenant = UUID.fromString("44444444-4444-4444-4444-444444444444");
        TenantContext.setTenantId(tenant);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("simulated failure"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> dataSourceConfig.applyTenantSetting(joinPoint));

        assertEquals("simulated failure", ex.getMessage());
        verify(jdbcTemplate).queryForObject(
                eq("select set_config('app.current_tenant', ?, true)"),
                eq(String.class),
                eq(tenant.toString()));
    }
}
