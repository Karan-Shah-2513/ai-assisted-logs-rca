package com.airca.multitenancy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class TenantApiKeyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantApiKeyService.class);

    private final Map<UUID, Set<String>> tenantKeyMap = new ConcurrentHashMap<>();

    public TenantApiKeyService(
            TenantSecurityProperties properties,
            Environment environment) {
        loadConfiguredKeys(properties, environment);
    }

    /**
     * Programmatic constructor for testing or manual initialization.
     */
    public TenantApiKeyService(Map<UUID, Set<String>> initialKeys) {
        if (initialKeys != null) {
            initialKeys.forEach((tenantId, keys) -> {
                Set<String> set = ConcurrentHashMap.newKeySet();
                set.addAll(keys);
                tenantKeyMap.put(tenantId, set);
            });
        }
    }

    private void loadConfiguredKeys(TenantSecurityProperties properties, Environment environment) {
        // 1. From properties map: airca.security.tenant-api-keys
        if (properties != null && properties.getTenantApiKeys() != null) {
            for (Map.Entry<String, String> entry : properties.getTenantApiKeys().entrySet()) {
                registerKeyString(entry.getKey(), entry.getValue());
            }
        }

        // 2. From raw mapping string if provided in properties
        if (properties != null && properties.getTenantApiKeysMapping() != null) {
            parseRawMapping(properties.getTenantApiKeysMapping());
        }

        // 3. Fallback / direct environment variables for known local compose tenants
        checkEnvVar(environment, "11111111-1111-1111-1111-111111111111",
                "AIRCA_TENANT_API_KEY_ALPHAFIN", "AIRCA_API_KEY_ALPHAFIN", "TENANT_API_KEY_ALPHAFIN");
        checkEnvVar(environment, "22222222-2222-2222-2222-222222222222",
                "AIRCA_TENANT_API_KEY_BETAPAY", "AIRCA_API_KEY_BETAPAY", "TENANT_API_KEY_BETAPAY");

        // 4. Also check if AIRCA_TENANT_API_KEYS was directly defined in environment
        if (environment != null) {
            String rawEnv = environment.getProperty("AIRCA_TENANT_API_KEYS");
            if (rawEnv != null && !rawEnv.isBlank()) {
                parseRawMapping(rawEnv);
            }
        }

        LOGGER.info("Initialized TenantApiKeyService with {} configured tenants (no secrets logged)",
                tenantKeyMap.size());
    }

    private void checkEnvVar(Environment environment, String tenantIdStr, String... envVarNames) {
        if (environment == null) {
            return;
        }
        for (String varName : envVarNames) {
            String val = environment.getProperty(varName);
            if (val != null && !val.isBlank()) {
                registerKeyString(tenantIdStr, val);
            }
        }
    }

    private void parseRawMapping(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] pairs = raw.split("[,;]");
        for (String pair : pairs) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int sep = trimmed.indexOf(':');
            if (sep == -1) {
                sep = trimmed.indexOf('=');
            }
            if (sep != -1) {
                String tenantStr = trimmed.substring(0, sep).trim();
                String keyStr = trimmed.substring(sep + 1).trim();
                registerKeyString(tenantStr, keyStr);
            }
        }
    }

    public void registerKey(UUID tenantId, String apiKey) {
        if (tenantId == null || apiKey == null || apiKey.isBlank()) {
            return;
        }
        tenantKeyMap.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet()).add(apiKey.trim());
    }

    private void registerKeyString(String tenantIdStr, String apiKeyVal) {
        if (tenantIdStr == null || tenantIdStr.isBlank() || apiKeyVal == null || apiKeyVal.isBlank()) {
            return;
        }
        try {
            UUID tenantId = UUID.fromString(tenantIdStr.trim());
            String[] keys = apiKeyVal.split(",");
            for (String k : keys) {
                if (!k.isBlank()) {
                    registerKey(tenantId, k.trim());
                }
            }
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Skipping invalid tenant UUID format in API key configuration: {}", tenantIdStr);
        }
    }

    /**
     * Resolves the authenticated tenant ID using constant-time key comparison.
     * Returns Optional.empty() if the key is null, blank, or invalid.
     * Never logs secret keys.
     */
    public Optional<UUID> resolveTenantId(String providedKey) {
        if (providedKey == null || providedKey.isBlank()) {
            return Optional.empty();
        }

        byte[] providedBytes = providedKey.trim().getBytes(StandardCharsets.UTF_8);
        UUID matchedTenant = null;

        // Iterate over all entries and all keys to preserve constant-time characteristics
        for (Map.Entry<UUID, Set<String>> entry : tenantKeyMap.entrySet()) {
            for (String configuredKey : entry.getValue()) {
                byte[] configuredBytes = configuredKey.getBytes(StandardCharsets.UTF_8);
                if (MessageDigest.isEqual(providedBytes, configuredBytes)) {
                    matchedTenant = entry.getKey();
                }
            }
        }

        if (matchedTenant == null) {
            LOGGER.warn("API key authentication failed: no matching tenant key found");
        }

        return Optional.ofNullable(matchedTenant);
    }

    public Set<UUID> getConfiguredTenants() {
        return Collections.unmodifiableSet(tenantKeyMap.keySet());
    }

    public boolean hasTenant(UUID tenantId) {
        return tenantKeyMap.containsKey(tenantId);
    }
}
