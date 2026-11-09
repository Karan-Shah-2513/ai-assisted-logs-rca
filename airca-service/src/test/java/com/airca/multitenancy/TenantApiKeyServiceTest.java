package com.airca.multitenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TenantApiKeyServiceTest {

    private final UUID alphaFinId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID betaPayId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    @DisplayName("Resolves tenant from configured properties map")
    void testResolvesTenantFromProperties() {
        TenantSecurityProperties props = new TenantSecurityProperties();
        props.setTenantApiKeys(Map.of(
                alphaFinId.toString(), "alpha-key-123",
                betaPayId.toString(), "beta-key-456"
        ));

        TenantApiKeyService service = new TenantApiKeyService(props, new MockEnvironment());

        Optional<UUID> resolvedAlpha = service.resolveTenantId("alpha-key-123");
        assertTrue(resolvedAlpha.isPresent());
        assertEquals(alphaFinId, resolvedAlpha.get());

        Optional<UUID> resolvedBeta = service.resolveTenantId("beta-key-456");
        assertTrue(resolvedBeta.isPresent());
        assertEquals(betaPayId, resolvedBeta.get());
    }

    @Test
    @DisplayName("Resolves tenant from raw mapping string")
    void testResolvesTenantFromRawMapping() {
        TenantSecurityProperties props = new TenantSecurityProperties();
        props.setTenantApiKeysMapping(alphaFinId + ":alpha-raw-key," + betaPayId + "=beta-raw-key");

        TenantApiKeyService service = new TenantApiKeyService(props, new MockEnvironment());

        assertEquals(Optional.of(alphaFinId), service.resolveTenantId("alpha-raw-key"));
        assertEquals(Optional.of(betaPayId), service.resolveTenantId("beta-raw-key"));
    }

    @Test
    @DisplayName("Resolves tenant from environment variables")
    void testResolvesTenantFromEnvironmentVariables() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("AIRCA_TENANT_API_KEY_ALPHAFIN", "alpha-env-key");
        env.setProperty("AIRCA_TENANT_API_KEY_BETAPAY", "beta-env-key");

        TenantApiKeyService service = new TenantApiKeyService(new TenantSecurityProperties(), env);

        assertEquals(Optional.of(alphaFinId), service.resolveTenantId("alpha-env-key"));
        assertEquals(Optional.of(betaPayId), service.resolveTenantId("beta-env-key"));
    }

    @Test
    @DisplayName("Returns empty optional for invalid, empty, or null keys (fails closed)")
    void testFailClosedOnInvalidOrMissingKeys() {
        TenantApiKeyService service = new TenantApiKeyService(Map.of(
                alphaFinId, Set.of("alpha-key-123")
        ));

        assertFalse(service.resolveTenantId(null).isPresent());
        assertFalse(service.resolveTenantId("").isPresent());
        assertFalse(service.resolveTenantId("   ").isPresent());
        assertFalse(service.resolveTenantId("wrong-key").isPresent());
        assertFalse(service.resolveTenantId("alpha-key-12").isPresent());
        assertFalse(service.resolveTenantId("alpha-key-1234").isPresent());
    }

    @Test
    @DisplayName("Handles whitespace in provided key cleanly")
    void testHandlesWhitespaceInProvidedKey() {
        TenantApiKeyService service = new TenantApiKeyService(Map.of(
                alphaFinId, Set.of("alpha-key-123")
        ));

        assertEquals(Optional.of(alphaFinId), service.resolveTenantId("  alpha-key-123  "));
    }

    @Test
    @DisplayName("Ignores invalid UUIDs in config without throwing exceptions")
    void testGracefulHandlingOfInvalidConfig() {
        TenantSecurityProperties props = new TenantSecurityProperties();
        props.setTenantApiKeys(Map.of(
                "not-a-valid-uuid", "some-key",
                alphaFinId.toString(), "alpha-valid-key"
        ));

        TenantApiKeyService service = new TenantApiKeyService(props, new MockEnvironment());
        assertEquals(Optional.of(alphaFinId), service.resolveTenantId("alpha-valid-key"));
        assertFalse(service.resolveTenantId("some-key").isPresent());
    }
}
