package com.airca.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.airca.ai.AiRcaService;
import com.airca.dto.IncidentDto;
import com.airca.dto.ResolveIncidentRequest;
import com.airca.model.Incident;
import com.airca.model.IncidentStatus;
import com.airca.model.RunbookChunk;
import com.airca.multitenancy.TenantContext;
import com.airca.rag.VectorRetrieverService;
import com.airca.repository.IncidentRepository;
import com.airca.repository.TenantRepository;
import com.airca.websocket.StompBroadcasterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

class IncidentControllerTest {

    private IncidentRepository incidentRepository;
    private TenantRepository tenantRepository;
    private TelemetryService telemetryService;
    private AiRcaService aiRcaService;
    private ObjectMapper objectMapper;
    private VectorRetrieverService vectorRetrieverService;
    private StompBroadcasterService broadcasterService;
    private IncidentController controller;
    private MockMvc mockMvc;

    private final UUID tenantId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID incidentId = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        tenantRepository = mock(TenantRepository.class);
        telemetryService = mock(TelemetryService.class);
        aiRcaService = mock(AiRcaService.class);
        objectMapper = new ObjectMapper();
        vectorRetrieverService = mock(VectorRetrieverService.class);
        broadcasterService = mock(StompBroadcasterService.class);

        controller = new IncidentController(
                incidentRepository,
                tenantRepository,
                telemetryService,
                aiRcaService,
                objectMapper,
                vectorRetrieverService,
                broadcasterService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .build();

        TenantContext.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Incident createIncident(UUID id, UUID tenant, IncidentStatus status) {
        Incident incident = new Incident();
        incident.setId(id);
        incident.setTenantId(tenant);
        incident.setFingerprintHash("abc123hash");
        incident.setServiceName("order-service");
        incident.setRawStackTrace("java.lang.RuntimeException: pool exhausted\n\tat com.airca.OrderService.pay()");
        incident.setSanitizedStackTrace("java.lang.RuntimeException: pool exhausted");
        incident.setOccurrenceCount(5);
        incident.setStatus(status);
        incident.setFirstSeen(Instant.parse("2026-11-09T10:00:00Z"));
        incident.setLastSeen(Instant.parse("2026-11-09T10:30:00Z"));
        return incident;
    }

    @Test
    void resolveIncident_rejectsEmptyOrMissingBodyWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/incidents/{id}/resolve", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(vectorRetrieverService);
        verifyNoInteractions(aiRcaService);
    }

    @Test
    void resolveIncident_rejectsBlankVerifiedRootCause() throws Exception {
        String payload = """
                {
                    "verifiedRootCause": "   ",
                    "verifiedFixCommandOrPR": "PR #123 merged",
                    "resolvedBy": "sre-alice"
                }
                """;

        mockMvc.perform(post("/api/v1/incidents/{id}/resolve", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(vectorRetrieverService);
    }

    @Test
    void resolveIncident_rejectsBlankVerifiedFixCommandOrPR() throws Exception {
        String payload = """
                {
                    "verifiedRootCause": "Connection pool leaked unclosed sockets",
                    "verifiedFixCommandOrPR": "   ",
                    "resolvedBy": "sre-alice"
                }
                """;

        mockMvc.perform(post("/api/v1/incidents/{id}/resolve", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(vectorRetrieverService);
    }

    @Test
    void resolveIncident_rejectsBlankResolvedBy() throws Exception {
        String payload = """
                {
                    "verifiedRootCause": "Connection pool leaked unclosed sockets",
                    "verifiedFixCommandOrPR": "PR #123 merged",
                    "resolvedBy": "   "
                }
                """;

        mockMvc.perform(post("/api/v1/incidents/{id}/resolve", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(vectorRetrieverService);
    }

    @Test
    void resolveIncident_rejectsMissingFields() throws Exception {
        String payload = """
                {
                    "verifiedRootCause": "Connection pool leak"
                }
                """;

        mockMvc.perform(post("/api/v1/incidents/{id}/resolve", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());

        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(vectorRetrieverService);
    }

    @Test
    void resolveIncident_successfulHumanVerifiedResolution_indexesHumanFieldsAndTransitionsStatus() throws Exception {
        Incident incident = createIncident(incidentId, tenantId, IncidentStatus.OPEN);
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String payload = """
                {
                    "verifiedRootCause": "Connection leak due to unclosed Statement in batch refund handler",
                    "verifiedFixCommandOrPR": "gh pr checkout 108 && ./mvnw clean test && kubectl rollout restart deployment/order-service",
                    "resolvedBy": "alice@alphafin.com"
                }
                """;

        mockMvc.perform(post("/api/v1/incidents/{id}/resolve", incidentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.serviceName").value("order-service"));

        assertEquals(IncidentStatus.RESOLVED, incident.getStatus());
        verify(incidentRepository).save(incident);

        ArgumentCaptor<UUID> tenantCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> incidentIdCaptor = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> runbookNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resolutionTextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);

        verify(vectorRetrieverService).indexResolvedIncident(
                tenantCaptor.capture(),
                incidentIdCaptor.capture(),
                runbookNameCaptor.capture(),
                resolutionTextCaptor.capture(),
                sourceCaptor.capture());

        assertEquals(tenantId, tenantCaptor.getValue());
        assertEquals(incidentId, incidentIdCaptor.getValue());
        assertTrue(runbookNameCaptor.getValue().contains("order-service"));
        assertTrue(runbookNameCaptor.getValue().contains(incidentId.toString()));

        String indexedText = resolutionTextCaptor.getValue();
        assertTrue(indexedText.contains("Connection leak due to unclosed Statement in batch refund handler"));
        assertTrue(indexedText.contains("gh pr checkout 108 && ./mvnw clean test && kubectl rollout restart deployment/order-service"));
        assertTrue(indexedText.contains("alice@alphafin.com"));
        assertTrue(indexedText.contains("order-service"));
        assertTrue(indexedText.contains(incident.getSanitizedStackTrace()));

        assertEquals("HUMAN_VERIFIED_INCIDENT", sourceCaptor.getValue());

        verifyNoInteractions(aiRcaService);
        verify(broadcasterService).broadcastIncident(any(IncidentDto.class));
    }

    @Test
    void resolveIncident_doesNotInvokeAiRcaServiceOrIndexLlmSuggestionsEvenIfAiRcaJsonIsNull() {
        Incident incident = createIncident(incidentId, tenantId, IncidentStatus.OPEN);
        incident.setAiRcaJson(null);
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResolveIncidentRequest request = new ResolveIncidentRequest(
                "Human identified deadlock",
                "PR #99 applied",
                "bob@alphafin.com");

        IncidentDto result = controller.resolveIncident(incidentId, request);

        assertNotNull(result);
        assertEquals(IncidentStatus.RESOLVED, result.status());

        verifyNoInteractions(aiRcaService);
        verify(aiRcaService, never()).analyze(any());

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorRetrieverService).indexResolvedIncident(
                eq(tenantId),
                eq(incidentId),
                anyString(),
                textCaptor.capture(),
                eq(RunbookChunk.SOURCE_HUMAN_VERIFIED_INCIDENT));

        String indexedText = textCaptor.getValue();
        assertTrue(indexedText.contains("Human identified deadlock"));
        assertTrue(indexedText.contains("PR #99 applied"));
        assertTrue(indexedText.contains("bob@alphafin.com"));
        assertFalse(indexedText.toLowerCase().contains("suggestedremediation"));
        assertFalse(indexedText.toLowerCase().contains("rootcausesummary"));
    }

    @Test
    void resolveIncident_idempotentRetry_allowsRepeatedResolutionAndUpdatesIndexedEntry() {
        Incident incident = createIncident(incidentId, tenantId, IncidentStatus.RESOLVED);
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResolveIncidentRequest firstAttempt = new ResolveIncidentRequest(
                "Initial root cause hypothesis",
                "Temporary restart",
                "charlie@alphafin.com");

        IncidentDto firstResult = controller.resolveIncident(incidentId, firstAttempt);
        assertEquals(IncidentStatus.RESOLVED, firstResult.status());

        ResolveIncidentRequest retryAttempt = new ResolveIncidentRequest(
                "Final verified root cause: connection pool config starvation",
                "PR #150 merged and deployment updated",
                "charlie@alphafin.com");

        IncidentDto retryResult = controller.resolveIncident(incidentId, retryAttempt);
        assertEquals(IncidentStatus.RESOLVED, retryResult.status());

        ArgumentCaptor<String> resolutionCaptor = ArgumentCaptor.forClass(String.class);
        verify(vectorRetrieverService, times(2)).indexResolvedIncident(
                eq(tenantId),
                eq(incidentId),
                anyString(),
                resolutionCaptor.capture(),
                eq("HUMAN_VERIFIED_INCIDENT"));

        assertEquals(2, resolutionCaptor.getAllValues().size());
        assertTrue(resolutionCaptor.getAllValues().get(1).contains("Final verified root cause: connection pool config starvation"));
        assertTrue(resolutionCaptor.getAllValues().get(1).contains("PR #150 merged and deployment updated"));

        verifyNoInteractions(aiRcaService);
    }

    @Test
    void resolveIncident_throws404WhenIncidentNotFoundForTenant() {
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.empty());

        ResolveIncidentRequest request = new ResolveIncidentRequest(
                "Verified cause",
                "Fix command",
                "user@example.com");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.resolveIncident(incidentId, request));

        assertEquals(404, ex.getStatusCode().value());
        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(vectorRetrieverService);
        verifyNoInteractions(aiRcaService);
    }

    @Test
    void resolveIncident_preservesRollbackWhenVectorIndexingThrowsException() {
        Incident incident = createIncident(incidentId, tenantId, IncidentStatus.OPEN);
        when(incidentRepository.findByIdAndTenantId(incidentId, tenantId)).thenReturn(Optional.of(incident));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        org.mockito.Mockito.doThrow(new IllegalStateException("Gemini embedding API unreachable"))
                .when(vectorRetrieverService).indexResolvedIncident(any(), any(), any(), any(), any());

        ResolveIncidentRequest request = new ResolveIncidentRequest(
                "Root cause",
                "Fix command",
                "dev@example.com");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> controller.resolveIncident(incidentId, request));

        assertTrue(ex.getMessage().contains("Gemini embedding"));
        verifyNoInteractions(aiRcaService);
        verify(broadcasterService, never()).broadcastIncident(any());
    }
}
