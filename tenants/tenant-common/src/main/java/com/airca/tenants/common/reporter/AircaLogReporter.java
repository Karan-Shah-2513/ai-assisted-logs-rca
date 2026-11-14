package com.airca.tenants.common.reporter;

import com.airca.tenants.common.config.TenantAircaProperties;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

public class AircaLogReporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AircaLogReporter.class);

    private final TenantAircaProperties properties;
    private final RestClient restClient;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public AircaLogReporter(TenantAircaProperties properties, RestClient restClient, ExecutorService executor) {
        this.properties = properties != null ? properties : new TenantAircaProperties();
        this.restClient = restClient;
        if (executor != null) {
            this.executor = executor;
            this.ownsExecutor = false;
        } else {
            this.executor = createDefaultExecutor(this.properties);
            this.ownsExecutor = true;
        }
    }

    public AircaLogReporter(TenantAircaProperties properties, RestClient restClient) {
        this(properties, restClient, null);
    }

    public AircaLogReporter(TenantAircaProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder.build(), null);
    }

    public AircaLogReporter(TenantAircaProperties properties, RestClient.Builder restClientBuilder, ExecutorService executor) {
        this(properties, restClientBuilder.build(), executor);
    }

    private ExecutorService createDefaultExecutor(TenantAircaProperties props) {
        AtomicInteger threadCount = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(String.format("airca-reporter-%d", threadCount.getAndIncrement()));
            thread.setDaemon(true);
            return thread;
        };

        int corePool = Math.max(1, props.getCorePoolSize());
        int maxPool = Math.max(corePool, props.getMaxPoolSize());
        int queueCapacity = Math.max(1, props.getQueueCapacity());

        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(queueCapacity);

        return new ThreadPoolExecutor(
                corePool,
                maxPool,
                60L,
                TimeUnit.SECONDS,
                queue,
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Reports an error log to the Airca ingestion endpoint.
     * When async is enabled (default), dispatches asynchronously via the dedicated bounded executor pool
     * so that tenant request threads never block on log shipping.
     *
     * @return true if accepted/dispatched (or if synchronous HTTP 2xx succeeded), false if disabled, rejected, or HTTP failed.
     */
    public boolean reportError(String serviceName, String stackTrace, String correlationId) {
        if (!properties.isEnabled()) {
            log.debug("Airca error log shipping is disabled by configuration");
            return false;
        }

        if (properties.isAsync()) {
            CompletableFuture<Boolean> future = reportErrorAsync(serviceName, stackTrace, correlationId);
            if (future.isDone()) {
                return Boolean.TRUE.equals(future.getNow(false));
            }
            return true;
        } else {
            return reportErrorSync(serviceName, stackTrace, correlationId);
        }
    }

    /**
     * Reports an error log asynchronously, returning a CompletableFuture containing the result.
     * Never throws exceptions directly to the caller thread.
     */
    public CompletableFuture<Boolean> reportErrorAsync(String serviceName, String stackTrace, String correlationId) {
        if (!properties.isEnabled()) {
            log.debug("Airca error log shipping is disabled by configuration");
            return CompletableFuture.completedFuture(false);
        }

        String effectiveServiceName = resolveServiceName(serviceName);
        String effectiveCorrelationId = resolveCorrelationId(correlationId);
        String payloadStackTrace = sanitizeStackTrace(stackTrace);

        if (!properties.isAsync()) {
            return CompletableFuture.completedFuture(
                    reportErrorSync(effectiveServiceName, payloadStackTrace, effectiveCorrelationId)
            );
        }

        try {
            return CompletableFuture.supplyAsync(
                    () -> reportErrorSync(effectiveServiceName, payloadStackTrace, effectiveCorrelationId),
                    executor
            ).exceptionally(ex -> {
                log.error("Unhandled error reporting log to Airca asynchronously [service={}, correlationId={}]: {}",
                        effectiveServiceName, effectiveCorrelationId, ex.getMessage(), ex);
                return false;
            });
        } catch (RejectedExecutionException rex) {
            log.warn("Airca error log reporter queue is full or executor rejected task [service={}, correlationId={}]: {}",
                    effectiveServiceName, effectiveCorrelationId, rex.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Synchronously sends an error log to the Airca ingestion endpoint.
     * Serializes serviceName, stackTrace, and correlationId to the Airca POST /api/v1/logs JSON contract,
     * and sends configured X-Tenant-ID and X-API-Key headers.
     * Explicitly logs failures; does not silently drop them.
     */
    public boolean reportErrorSync(String serviceName, String stackTrace, String correlationId) {
        if (!properties.isEnabled()) {
            log.debug("Airca error log shipping is disabled by configuration");
            return false;
        }

        String effectiveServiceName = resolveServiceName(serviceName);
        String effectiveCorrelationId = resolveCorrelationId(correlationId);
        String payloadStackTrace = sanitizeStackTrace(stackTrace);

        String aircaUrl = properties.getAircaUrl();
        String tenantId = properties.getTenantId();
        String apiKey = properties.getApiKey();

        try {
            LogIngestPayload payload = new LogIngestPayload(
                    effectiveServiceName,
                    payloadStackTrace,
                    effectiveCorrelationId
            );

            log.info("Reporting error log to Airca [endpoint={}, tenantId={}, service={}, correlationId={}]",
                    aircaUrl, tenantId, effectiveServiceName, effectiveCorrelationId);

            var requestSpec = restClient.post()
                    .uri(aircaUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload);

            if (tenantId != null && !tenantId.isBlank()) {
                requestSpec.header("X-Tenant-ID", tenantId.trim());
            }
            if (apiKey != null && !apiKey.isBlank()) {
                requestSpec.header("X-API-Key", apiKey.trim());
            }

            ResponseEntity<Void> response = requestSpec.retrieve().toBodilessEntity();

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully reported error log to Airca for correlationId={}", effectiveCorrelationId);
                return true;
            } else {
                log.error("Failed to report error log to Airca: non-2xx status {} received for correlationId={}",
                        response.getStatusCode(), effectiveCorrelationId);
                return false;
            }
        } catch (Exception ex) {
            // Explicitly log reporting failure; don't silently drop it.
            log.error("Failed to report error log to Airca [endpoint={}, tenantId={}, service={}, correlationId={}]: {}",
                    aircaUrl, tenantId, effectiveServiceName, effectiveCorrelationId, ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Alias for reportError.
     */
    public boolean shipError(String serviceName, String stackTrace, String correlationId) {
        return reportError(serviceName, stackTrace, correlationId);
    }

    /**
     * Alias for reportErrorAsync.
     */
    public CompletableFuture<Boolean> shipErrorAsync(String serviceName, String stackTrace, String correlationId) {
        return reportErrorAsync(serviceName, stackTrace, correlationId);
    }

    public String resolveServiceName(String serviceName) {
        return (serviceName != null && !serviceName.isBlank())
                ? serviceName.trim()
                : (properties.getServiceName() != null ? properties.getServiceName().trim() : "unknown-service");
    }

    public String resolveCorrelationId(String correlationId) {
        return (correlationId != null && !correlationId.isBlank())
                ? correlationId.trim()
                : UUID.randomUUID().toString();
    }

    public String sanitizeStackTrace(String stackTrace) {
        String payloadStackTrace = stackTrace != null ? stackTrace : "";
        if (payloadStackTrace.length() > 95_000) {
            payloadStackTrace = payloadStackTrace.substring(0, 95_000) + "\n...[truncated]";
        }
        return payloadStackTrace;
    }

    public TenantAircaProperties getProperties() {
        return properties;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public ExecutorService getExecutorService() {
        return executor;
    }

    public boolean isShutdown() {
        return executor == null || executor.isShutdown();
    }

    public boolean isTerminated() {
        return executor == null || executor.isTerminated();
    }

    @PreDestroy
    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        if (executor != null && ownsExecutor && !executor.isShutdown()) {
            log.info("Shutting down AircaLogReporter executor pool...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(properties.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                    log.warn("AircaLogReporter executor pool did not terminate within {} ms; forcing shutdown.",
                            properties.getShutdownTimeoutMs());
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while awaiting AircaLogReporter executor termination; forcing shutdown.");
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
