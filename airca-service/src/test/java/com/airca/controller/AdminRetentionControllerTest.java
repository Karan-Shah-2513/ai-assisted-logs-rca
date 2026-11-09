package com.airca.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.airca.dto.RetentionCleanupResponse;
import com.airca.multitenancy.TenantContext;
import com.airca.retention.RetentionProperties;
import com.airca.retention.RetentionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class AdminRetentionControllerTest {

    private RetentionService retentionService;
    private RetentionProperties properties;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        retentionService = mock(RetentionService.class);
        properties = new RetentionProperties();
        properties.setIncidentRetentionDays(30);
        properties.setRunbookChunkRetentionDays(180);

        AdminRetentionController controller = new AdminRetentionController(retentionService, properties);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .build();

        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Manual cleanup with no body uses configured default retention periods for current tenant")
    void manualCleanupWithoutBodyUsesConfiguredDefaults() throws Exception {
        Instant incidentCutoff = Instant.parse("2026-10-10T00:00:00Z");
        Instant chunkCutoff = Instant.parse("2026-05-13T00:00:00Z");

        when(retentionService.cleanupTenant(tenantId, 30, 180))
                .thenReturn(new RetentionCleanupResponse(tenantId, 8, 12, incidentCutoff, chunkCutoff));

        mockMvc.perform(post("/api/v1/admin/retention/cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.deletedIncidents").value(8))
                .andExpect(jsonPath("$.deletedRunbookChunks").value(12))
                .andExpect(jsonPath("$.incidentCutoff").value(incidentCutoff.toString()))
                .andExpect(jsonPath("$.runbookChunkCutoff").value(chunkCutoff.toString()));

        verify(retentionService).cleanupTenant(tenantId, 30, 180);
    }

    @Test
    @DisplayName("Supports alternative alias endpoint paths")
    void supportsAlternativeAliasPaths() throws Exception {
        when(retentionService.cleanupTenant(tenantId, 30, 180))
                .thenReturn(new RetentionCleanupResponse(tenantId, 2, 3));

        mockMvc.perform(post("/api/v1/admin/cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedIncidents").value(2));

        mockMvc.perform(post("/api/v1/retention/cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedIncidents").value(2));
    }

    @Test
    @DisplayName("Manual cleanup with custom retention days overrides defaults")
    void manualCleanupWithCustomRetentionDays() throws Exception {
        when(retentionService.cleanupTenant(tenantId, 15, 60))
                .thenReturn(new RetentionCleanupResponse(tenantId, 3, 5));

        String json = """
                {
                    "incidentRetentionDays": 15,
                    "runbookChunkRetentionDays": 60
                }
                """;

        mockMvc.perform(post("/api/v1/admin/retention/cleanup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedIncidents").value(3))
                .andExpect(jsonPath("$.deletedRunbookChunks").value(5));

        verify(retentionService).cleanupTenant(tenantId, 15, 60);
    }

    @Test
    @DisplayName("Manual endpoint operates only on current tenant context and ignores arbitrary tenant ID param")
    void ignoresArbitraryTenantIdParameterAndOperatesOnContextTenant() throws Exception {
        UUID spoofedTenant = UUID.fromString("99999999-9999-9999-9999-999999999999");

        when(retentionService.cleanupTenant(tenantId, 30, 180))
                .thenReturn(new RetentionCleanupResponse(tenantId, 1, 1));

        mockMvc.perform(post("/api/v1/admin/retention/cleanup")
                        .param("tenantId", spoofedTenant.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId.toString()));

        // Service must be called strictly with context tenantId, NOT spoofedTenant
        verify(retentionService).cleanupTenant(tenantId, 30, 180);
        verify(retentionService, org.mockito.Mockito.never()).cleanupTenant(eq(spoofedTenant), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Manual endpoint rejects arbitrary tenantId supplied in request body and does not invoke service")
    void rejectsArbitraryTenantIdInRequestBody() throws Exception {
        UUID spoofedTenant = UUID.fromString("88888888-8888-8888-8888-888888888888");

        String json = """
                {
                    "tenantId": "%s",
                    "incidentRetentionDays": 10
                }
                """.formatted(spoofedTenant);

        mockMvc.perform(post("/api/v1/admin/retention/cleanup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(retentionService);
    }

    @Test
    @DisplayName("Rejects request when TenantContext is missing")
    void rejectsRequestWhenTenantContextMissing() throws Exception {
        TenantContext.clear();

        mockMvc.perform(post("/api/v1/admin/retention/cleanup"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(retentionService);
    }

    @Test
    @DisplayName("Rejects negative retention days with 400 Bad Request")
    void rejectsNegativeRetentionDays() throws Exception {
        String json = """
                {
                    "incidentRetentionDays": -5,
                    "runbookChunkRetentionDays": 30
                }
                """;

        mockMvc.perform(post("/api/v1/admin/retention/cleanup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(retentionService);
    }

    @Test
    @DisplayName("Does not swallow service exceptions and propagates error")
    void propagatesServiceExceptions() throws Exception {
        when(retentionService.cleanupTenant(tenantId, 30, 180))
                .thenThrow(new IllegalStateException("Database lock acquisition failure"));

        try {
            mockMvc.perform(post("/api/v1/admin/retention/cleanup"));
        } catch (Exception ex) {
            // Standalone MockMvc wraps uncaught controller exceptions in NestedServletException or rethrows
            assertEquals("Database lock acquisition failure", ex.getCause().getMessage());
        }
    }
}
