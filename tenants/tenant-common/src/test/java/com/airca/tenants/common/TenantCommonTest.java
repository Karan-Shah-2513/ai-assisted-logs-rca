package com.airca.tenants.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

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
        properties.setAsync(true);
        properties.setAircaUrl("http://localhost:8080/api/v1/logs");
        properties.setTenantId("11111111-1111-1111-1111-111111111111");
        properties.setApiKey("test-api-key");
        properties.setServiceName("test-service");

        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        reporter = new AircaLogReporter(properties, restClientBuilder);
        handler = new TenantGlobalExceptionHandler(reporter, properties);
    }

    @AfterEach
    void tearDown() {
        if (reporter != null) {
            reporter.close();
        }
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

        // Verify calling request thread got immediate 500 response
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo("custom-corr-123");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().correlationId()).isEqualTo("custom-corr-123");

        // Verify async HTTP POST completes via background executor
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> mockServer.verify());
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

        // Must still return generic error response immediately, not blow up
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("corr-failover");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> mockServer.verify());
    }

    @Test
    void testNonBlockingExecutionAndThreadNaming() {
        AtomicReference<String> workerThreadName = new AtomicReference<>();
        String callingThreadName = Thread.currentThread().getName();

        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    workerThreadName.set(Thread.currentThread().getName());
                    // Simulate server-side latency
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON).createResponse(request);
                });

        long startTime = System.currentTimeMillis();
        boolean accepted = reporter.reportError("test-service", "test trace", "corr-thread-test");
        long elapsedTime = System.currentTimeMillis() - startTime;

        // The calling thread must return immediately without waiting for the 100ms HTTP round-trip
        assertThat(accepted).isTrue();
        assertThat(elapsedTime).isLessThan(80);

        // Await background execution and verify dedicated thread pool naming
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            mockServer.verify();
            assertThat(workerThreadName.get())
                    .isNotNull()
                    .startsWith("airca-reporter-")
                    .isNotEqualTo(callingThreadName);
        });
    }

    @Test
    void testReportErrorAsyncCompletableFuture() throws Exception {
        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON));

        CompletableFuture<Boolean> future = reporter.reportErrorAsync("test-service", "async trace", "corr-async-1");
        assertThat(future).isNotNull();
        Boolean result = future.get(3, TimeUnit.SECONDS);
        assertThat(result).isTrue();

        mockServer.verify();
    }

    @Test
    void testReportErrorAsyncFailureDoesNotThrow() throws Exception {
        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        CompletableFuture<Boolean> future = reporter.reportErrorAsync("test-service", "async trace", "corr-async-2");
        assertThat(future).isNotNull();
        Boolean result = future.get(3, TimeUnit.SECONDS);
        // Returns false on HTTP failure without exceptional completion
        assertThat(result).isFalse();

        mockServer.verify();
    }

    @Test
    void testQueueingAndBoundedQueueRejection() throws Exception {
        // Set bounded queue capacity = 2, pool sizes = 1
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(2);

        // Re-create reporter with tiny bounded queue
        reporter.close();
        reporter = new AircaLogReporter(properties, restClientBuilder);

        CountDownLatch blockerLatch = new CountDownLatch(1);
        CountDownLatch requestStartedLatch = new CountDownLatch(1);

        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andRespond(request -> {
                    requestStartedLatch.countDown();
                    try {
                        blockerLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON).createResponse(request);
                });

        // Mock 2 queued requests
        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andRespond(withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andRespond(withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON));

        // Task 1: blocks the 1 core thread
        boolean submitted1 = reporter.reportError("test-service", "trace 1", "corr-1");
        assertThat(submitted1).isTrue();
        assertThat(requestStartedLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Task 2 and 3: fill queue capacity (2 items)
        boolean submitted2 = reporter.reportError("test-service", "trace 2", "corr-2");
        boolean submitted3 = reporter.reportError("test-service", "trace 3", "corr-3");
        assertThat(submitted2).isTrue();
        assertThat(submitted3).isTrue();

        // Task 4: exceeds queue capacity -> rejected gracefully without throwing
        boolean submitted4 = reporter.reportError("test-service", "trace 4", "corr-4");
        assertThat(submitted4).isFalse();

        // CompletableFuture async method also completes with false on rejection
        CompletableFuture<Boolean> future5 = reporter.reportErrorAsync("test-service", "trace 5", "corr-5");
        assertThat(future5.get(2, TimeUnit.SECONDS)).isFalse();

        // Release the blocked worker thread so queued tasks complete
        blockerLatch.countDown();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> mockServer.verify());
    }

    @Test
    void testGracefulShutdown() {
        assertThat(reporter.getExecutor().isShutdown()).isFalse();

        reporter.shutdown();
        assertThat(reporter.getExecutor().isShutdown()).isTrue();

        // Submissions after shutdown return false cleanly without throwing
        boolean accepted = reporter.reportError("test-service", "trace after shutdown", "corr-shut");
        assertThat(accepted).isFalse();

        CompletableFuture<Boolean> future = reporter.reportErrorAsync("test-service", "trace after shutdown", "corr-shut-async");
        assertThat(future.join()).isFalse();
    }

    @Test
    void testSynchronousModeWhenAsyncDisabled() {
        properties.setAsync(false);

        AtomicReference<String> executedThreadName = new AtomicReference<>();
        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    executedThreadName.set(Thread.currentThread().getName());
                    return withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON).createResponse(request);
                });

        boolean success = reporter.reportError("test-service", "sync trace", "corr-sync");
        assertThat(success).isTrue();

        // In sync mode, mockServer was already called before reportError returned
        mockServer.verify();
        assertThat(executedThreadName.get()).isEqualTo(Thread.currentThread().getName());
    }

    @Test
    void testDisabledLogShipping() {
        properties.setEnabled(false);

        boolean reported = reporter.reportError("test-service", "disabled trace", "corr-disabled");
        assertThat(reported).isFalse();

        CompletableFuture<Boolean> future = reporter.reportErrorAsync("test-service", "disabled trace", "corr-disabled-async");
        assertThat(future.join()).isFalse();

        // No HTTP request should have been dispatched
        mockServer.verify();
    }

    @Test
    void testLargeStackTraceTruncation() {
        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.stackTrace").value(org.hamcrest.Matchers.endsWith("...[truncated]")))
                .andRespond(withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON));

        String hugeTrace = "X".repeat(100_000);
        reporter.reportError("test-service", hugeTrace, "corr-huge");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> mockServer.verify());
    }

    @Test
    void testResponseStatusException5xxShippedAnd4xxIgnored() {
        // 5xx status should be shipped
        mockServer.expect(requestTo("http://localhost:8080/api/v1/logs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.correlationId").value("corr-503"))
                .andRespond(withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON));

        MockHttpServletRequest request503 = new MockHttpServletRequest("GET", "/test/service-unavailable");
        request503.addHeader("X-Correlation-ID", "corr-503");
        ResponseStatusException ex503 = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Service Down");

        ResponseEntity<ErrorResponse> response503 = handler.handleResponseStatusException(ex503, request503);
        assertThat(response503.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> mockServer.verify());

        // 4xx status should NOT be shipped
        MockHttpServletRequest request404 = new MockHttpServletRequest("GET", "/test/not-found");
        request404.addHeader("X-Correlation-ID", "corr-404");
        ResponseStatusException ex404 = new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found");

        ResponseEntity<ErrorResponse> response404 = handler.handleResponseStatusException(ex404, request404);
        assertThat(response404.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Verify no additional HTTP requests were made
        mockServer.verify();
    }
}
