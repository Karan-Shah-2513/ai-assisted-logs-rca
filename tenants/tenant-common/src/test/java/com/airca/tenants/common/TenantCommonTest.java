package com.airca.tenants.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.airca.tenants.common.advice.ErrorResponse;
import com.airca.tenants.common.advice.TenantGlobalExceptionHandler;
import com.airca.tenants.common.config.TenantAircaProperties;
import com.airca.tenants.common.reporter.AircaLogReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TenantCommonTest {

    private TenantAircaProperties properties;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private AircaLogReporter reporter;
    private TenantGlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        properties = new TenantAircaProperties();
        properties.setEnabled(true);
        properties.setAircaUrl("http://localhost:8080/api/v1/logs");
        properties.setTenantId("11111111-1111-1111-1111-111111111111");
        properties.setApiKey("test-api-key");
        properties.setServiceName("test-service");

        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        reporter = new AircaLogReporter(properties, restClientBuilder);
        handler = new TenantGlobalExceptionHandler(reporter, properties);
    }

    @Test
    void testSuccessfulShippingOnUnhandledException() {
        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Tenant-ID", "11111111-1111-1111-1111-111111111111"))
                .andExpect(header("X-API-Key", "test-api-key"))
                .andExpect(jsonPath("$.serviceName").value("test-service"))
                .andExpect(jsonPath("$.correlationId").value("custom-corr-123"))
                .andExpect(jsonPath("$.stackTrace").isNotEmpty())
                .andRespond(withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test/fail");
        request.addHeader("X-Correlation-ID", "custom-corr-123");

        Exception exception = new RuntimeException("Simulated catastrophic failure in test");
        ResponseEntity<ErrorResponse> response = handler.handleUnhandledThrowable(exception, request);

        mockServer.verify();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("custom-corr-123");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().correlationId()).isEqualTo("custom-corr-123");
    }

    @Test
    void testResilienceWhenAircaFails() {
        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test/fail");
        request.addHeader("X-Correlation-ID", "corr-failover");

        Exception exception = new IllegalStateException("Database state invalid");
        ResponseEntity<ErrorResponse> response = handler.handleUnhandledThrowable(exception, request);

        mockServer.verify();

        // Must still return generic error response, not blow up
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("corr-failover");
    }
}
