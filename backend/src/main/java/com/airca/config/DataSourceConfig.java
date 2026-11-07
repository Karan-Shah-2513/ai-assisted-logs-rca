package com.airca.config;

import com.airca.multitenancy.TenantContext;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataSourceConfig {

    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final UUID defaultTenantId;

    public DataSourceConfig(
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate,
            @Value("${airca.multitenancy.default-tenant-id}") UUID defaultTenantId) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
        this.defaultTenantId = defaultTenantId;
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional) "
            + "|| @within(org.springframework.transaction.annotation.Transactional)")
    public Object applyTenantSetting(ProceedingJoinPoint joinPoint) throws Throwable {
        UUID tenantId = TenantContext.getTenantId().orElse(defaultTenantId);
        return transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject(
                    "select set_config('app.current_tenant', ?, true)",
                    String.class,
                    tenantId.toString());
            try {
                return joinPoint.proceed();
            } catch (Throwable throwable) {
                return DataSourceConfig.<RuntimeException, Object>throwUnchecked(throwable);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R throwUnchecked(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
