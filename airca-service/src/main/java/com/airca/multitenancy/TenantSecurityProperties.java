package com.airca.multitenancy;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "airca.security")
public class TenantSecurityProperties {

    /**
     * Map of tenant UUID string -> API key string.
     * Backed by environment variables or configuration properties.
     */
    private Map<String, String> tenantApiKeys = new HashMap<>();

    /**
     * Optional comma or semicolon-separated mapping of tenantId:apiKey or tenantId=apiKey.
     */
    private String tenantApiKeysMapping;

    public Map<String, String> getTenantApiKeys() {
        return tenantApiKeys;
    }

    public void setTenantApiKeys(Map<String, String> tenantApiKeys) {
        this.tenantApiKeys = tenantApiKeys != null ? tenantApiKeys : new HashMap<>();
    }

    public String getTenantApiKeysMapping() {
        return tenantApiKeysMapping;
    }

    public void setTenantApiKeysMapping(String tenantApiKeysMapping) {
        this.tenantApiKeysMapping = tenantApiKeysMapping;
    }
}
