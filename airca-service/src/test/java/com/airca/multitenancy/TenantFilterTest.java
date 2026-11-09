package com.airca.multitenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.airca.controller.HealthController;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class TenantFilterTest {

    private final UUID alphaFinId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID betaPayId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final String alphaFinKey = "alphafin-secret-key-1111";
    private final String betaPayKey = "betapay-secret-key-2222";

    private TenantApiKeyService apiKeyService;
    private TenantFilter tenantFilter;
    private MockMvc mockMvc;
    private final AtomicReference<UUID> capturedContextTenant = new AtomicReference<>();

    @RestController
    @RequestMapping("/api/v1")
    class TestApiController {

        @GetMapping("/incidents")
        public ResponseEntity<Map<String, Object>> getIncidents() {
            UUID tenant = TenantContext.getTenantId().orElse(null);
            capturedContextTenant.set(tenant);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "tenantId", tenant != null ? tenant.toString() : "none"
            ));
        }

        @PostMapping("/admin/retention/cleanup")
        public ResponseEntity<Map<String, Object>> cleanup() {
            UUID tenant = TenantContext.getTenantId().orElse(null);
            capturedContextTenant.set(tenant);
            return ResponseEntity.ok(Map.of("cleaned", true, "tenantId", tenant != null ? tenant.toString() : "none"));
        }

        @GetMapping("/error-route")
        public ResponseEntity<Void> errorRoute() {
            capturedContextTenant.set(TenantContext.getTenantId().orElse(null));
            throw new RuntimeException("Simulated controller failure");
        }
    }

    @BeforeEach
    void setUp() {
        capturedContextTenant.set(null);
        TenantContext.clear();

        apiKeyService = new TenantApiKeyService(Map.of(
                alphaFinId, Set.of(alphaFinKey),
                betaPayId, Set.of(betaPayKey)
        ));
        tenantFilter = new TenantFilter(apiKeyService);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestApiController(), new HealthController())
                .addFilters(tenantFilter)
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Missing X-API-Key header returns 401 Unauthorized")
    void testMissingApiKeyReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized());

        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("Invalid X-API-Key header returns 401 Unauthorized")
    void testInvalidApiKeyReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("X-API-Key", "invalid-key-999"))
                .andExpect(status().isUnauthorized());

        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("Default tenant bypass prevention: sending X-Tenant-ID without X-API-Key must return 401")
    void testDefaultTenantBypassPrevention() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("X-Tenant-ID", alphaFinId.toString()))
                .andExpect(status().isUnauthorized());

        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("Valid X-API-Key without X-Tenant-ID succeeds and binds TenantContext to key's tenant")
    void testValidApiKeyWithoutTenantHeaderBindsTenantContext() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("X-API-Key", alphaFinKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.tenantId").value(alphaFinId.toString()));

        assertEquals(alphaFinId, capturedContextTenant.get());
        assertFalse(TenantContext.getTenantId().isPresent(), "TenantContext must be cleared in finally");
    }

    @Test
    @DisplayName("Valid X-API-Key with matching X-Tenant-ID succeeds")
    void testValidApiKeyWithMatchingTenantIdSucceeds() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("X-API-Key", alphaFinKey)
                        .header("X-Tenant-ID", alphaFinId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(alphaFinId.toString()));

        assertEquals(alphaFinId, capturedContextTenant.get());
        assertFalse(TenantContext.getTenantId().isPresent(), "TenantContext must be cleared in finally");
    }

    @Test
    @DisplayName("Second tenant with valid key and matching tenant ID succeeds and binds BetaPay")
    void testSecondTenantWithValidKeySucceeds() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("X-API-Key", betaPayKey)
                        .header("X-Tenant-ID", betaPayId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(betaPayId.toString()));

        assertEquals(betaPayId, capturedContextTenant.get());
        assertFalse(TenantContext.getTenantId().isPresent(), "TenantContext must be cleared in finally");
    }

    @Test
    @DisplayName("Tenant mismatch: X-Tenant-ID differing from key's tenant returns 403 Forbidden")
    void testTenantMismatchReturnsForbidden() throws Exception {
        // Authenticating with AlphaFin key but supplying BetaPay tenant ID
        mockMvc.perform(get("/api/v1/incidents")
                        .header("X-API-Key", alphaFinKey)
                        .header("X-Tenant-ID", betaPayId.toString()))
                .andExpect(status().isForbidden());

        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("Malformed X-Tenant-ID returns 400 Bad Request")
    void testMalformedTenantIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("X-API-Key", alphaFinKey)
                        .header("X-Tenant-ID", "not-a-valid-uuid"))
                .andExpect(status().isBadRequest());

        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("Non-canonical X-Tenant-ID returns 400 Bad Request")
    void testNonCanonicalTenantIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/incidents")
                        .header("X-API-Key", alphaFinKey)
                        .header("X-Tenant-ID", alphaFinId.toString() + "xyz"))
                .andExpect(status().isBadRequest());

        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("TenantContext cleanup: cleared even when downstream controller throws an exception")
    void testTenantContextClearedOnException() {
        try {
            mockMvc.perform(get("/api/v1/error-route")
                    .header("X-API-Key", alphaFinKey));
        } catch (Exception ignored) {
            // Expected exception from TestApiController.errorRoute
        }

        assertEquals(alphaFinId, capturedContextTenant.get());
        assertFalse(TenantContext.getTenantId().isPresent(), "TenantContext must be cleared in finally even on error");
    }

    @Test
    @DisplayName("Public non-API health endpoint is accessible without API key or tenant ID")
    void testPublicHealthEndpointsAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("Admin retention cleanup endpoint requires API key and fails closed without it")
    void testRetentionAdminRequiresApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/admin/retention/cleanup"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/retention/cleanup")
                        .header("X-API-Key", alphaFinKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleaned").value(true))
                .andExpect(jsonPath("$.tenantId").value(alphaFinId.toString()));

        assertEquals(alphaFinId, capturedContextTenant.get());
        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("CORS preflight OPTIONS requests are allowed without API key")
    void testCorsPreflightAllowedWithoutApiKey() throws Exception {
        mockMvc.perform(options("/api/v1/incidents"))
                .andExpect(status().isOk());

        assertFalse(TenantContext.getTenantId().isPresent());
    }
}
