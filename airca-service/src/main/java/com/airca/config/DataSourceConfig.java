package com.airca.config;

import com.airca.multitenancy.TenantContext;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
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

    public DataSourceConfig(
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Autowired
    public DataSourceConfig(
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate,
            @Value("${airca.multitenancy.default-tenant-id:#{null}}") UUID defaultTenantId) {
        this(transactionManager, jdbcTemplate);
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional) "
            + "|| @within(org.springframework.transaction.annotation.Transactional)")
    public Object applyTenantSetting(ProceedingJoinPoint joinPoint) throws Throwable {
        String tenantSetting = TenantContext.getTenantId()
                .map(UUID::toString)
                .orElse("");
        return transactionTemplate.execute(status -> {
            jdbcTemplate.queryForObject(
                    "select set_config('app.current_tenant', ?, true)",
                    String.class,
                    tenantSetting);
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
