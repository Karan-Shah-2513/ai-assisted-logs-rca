package com.airca.tenants.common.config;

import com.airca.tenants.common.advice.TenantGlobalExceptionHandler;
import com.airca.tenants.common.reporter.AircaLogReporter;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@EnableConfigurationProperties(TenantAircaProperties.class)
public class TenantCommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder restClientBuilder(TenantAircaProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(properties.getConnectTimeoutMs(), 1000)));
        factory.setReadTimeout(Duration.ofMillis(Math.max(properties.getReadTimeoutMs(), 1000)));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    @ConditionalOnMissingBean(AircaLogReporter.class)
    public AircaLogReporter aircaLogReporter(TenantAircaProperties properties, RestClient.Builder restClientBuilder) {
        return new AircaLogReporter(properties, restClientBuilder);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantGlobalExceptionHandler tenantGlobalExceptionHandler(
            AircaLogReporter aircaLogReporter, TenantAircaProperties properties) {
        return new TenantGlobalExceptionHandler(aircaLogReporter, properties);
    }
}
