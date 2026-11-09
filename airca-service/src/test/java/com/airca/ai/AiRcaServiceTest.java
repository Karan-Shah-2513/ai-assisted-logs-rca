package com.airca.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.airca.model.Incident;
import com.airca.rag.VectorRetrieverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AiRcaServiceTest {

    private final VectorRetrieverService retriever = mock(VectorRetrieverService.class);
    private final AiRcaService service = new AiRcaService(
            RestClient.builder(), new ObjectMapper(), retriever, "", "test-model");

    @Test
    void createsDeterministicPaymentRcaWhenApiKeyIsMissing() {
        Incident incident = new Incident();
        incident.setServiceName("payment-service");
        incident.setSanitizedStackTrace("java.sql.SQLException: deadlock detected");
        incident.setOccurrenceCount(4);
        when(retriever.retrieveContext(incident)).thenReturn("Runbook: Payment DB deadlock");

        var report = service.analyze(incident);

        assertEquals("P0", report.severity());
        assertTrue(report.rootCauseSummary().contains("Database contention"));
        assertTrue(report.confidenceScore() >= 0 && report.confidenceScore() <= 100);
    }
}
