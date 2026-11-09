package com.airca.tenants.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tenant.airca")
public class TenantAircaProperties {

    /**
     * Whether error shipping to Airca is enabled.
     */
    private boolean enabled = true;

    /**
     * The Airca log ingestion endpoint URL (e.g. http://localhost:8080/api/v1/logs).
     */
    private String aircaUrl = "http://localhost:8080/api/v1/logs";

    /**
     * The UUID of the tenant.
     */
    private String tenantId;

    /**
     * The tenant API key for authentication.
     */
    private String apiKey;

    /**
     * The microservice name (e.g. order-service).
     */
    private String serviceName;

    /**
     * HTTP connect timeout in milliseconds.
     */
    private int connectTimeoutMs = 3000;

    /**
     * HTTP read timeout in milliseconds.
     */
    private int readTimeoutMs = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAircaUrl() {
        return aircaUrl;
    }

    public void setAircaUrl(String aircaUrl) {
        this.aircaUrl = aircaUrl;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
