package com.airca.chaos;

import com.airca.ingestion.LogKafkaProducer;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ChaosSimulatorService {

    private final LogKafkaProducer kafkaProducer;

    public ChaosSimulatorService(LogKafkaProducer kafkaProducer) {
        this.kafkaProducer = kafkaProducer;
    }

    public String normalizeScenario(String scenario) {
        String normalized = scenario.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("PAYMENT_DB_DEADLOCK")
                && !normalized.equals("REDIS_OOM")
                && !normalized.equals("AUTH_NPE")) {
            throw new IllegalArgumentException(
                    "Unsupported scenario. Use PAYMENT_DB_DEADLOCK, REDIS_OOM, or AUTH_NPE");
        }
        return normalized;
    }

    @Async("chaosTaskExecutor")
    public CompletableFuture<Integer> simulate(String scenario, UUID tenantId, int logCount) {
        String normalized = normalizeScenario(scenario);
        ScenarioDetails details = scenarioDetails(normalized);
        for (int i = 0; i < logCount; i++) {
            String stackTrace = details.stackTrace(
                    UUID.randomUUID(),
                    Instant.now(),
                    randomIpv4(),
                    randomHexAddress(),
                    ThreadLocalRandom.current().nextInt(40, 280));
            kafkaProducer.publish(
                    tenantId,
                    details.serviceName(),
                    stackTrace,
                    UUID.randomUUID().toString());
        }
        return CompletableFuture.completedFuture(logCount);
    }

    private static ScenarioDetails scenarioDetails(String scenario) {
        return switch (scenario) {
            case "PAYMENT_DB_DEADLOCK" -> new ScenarioDetails(
                    "payment-service",
                    (id, timestamp, ip, address, line) -> timestamp + " ERROR requestId=" + id
                            + " client=" + ip + " java.sql.SQLException: deadlock detected while "
                            + "locking refund batch; connection pool exhausted at "
                            + "com.airca.payment.RefundService.processBatch(RefundService.java:"
                            + line + ") nativeAddress=" + address);
            case "REDIS_OOM" -> new ScenarioDetails(
                    "cache-service",
                    (id, timestamp, ip, address, line) -> timestamp + " ERROR requestId=" + id
                            + " client=" + ip + " RedisConnectionFailureException: OOM command not "
                            + "allowed when used memory exceeds maxmemory at "
                            + "com.airca.cache.ResponseCache.write(ResponseCache.java:"
                            + line + ") nativeAddress=" + address);
            case "AUTH_NPE" -> new ScenarioDetails(
                    "auth-service",
                    (id, timestamp, ip, address, line) -> timestamp + " ERROR requestId=" + id
                            + " client=" + ip + " java.lang.NullPointerException: session user is null at "
                            + "com.airca.auth.TokenRefreshHandler.refresh(TokenRefreshHandler.java:"
                            + line + ") nativeAddress=" + address);
            default -> throw new IllegalArgumentException("Unsupported scenario: " + scenario);
        };
    }

    private static String randomIpv4() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return random.nextInt(1, 224) + "." + random.nextInt(0, 256) + "."
                + random.nextInt(0, 256) + "." + random.nextInt(1, 255);
    }

    private static String randomHexAddress() {
        return "0x" + Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    private record ScenarioDetails(String serviceName, StackTraceFactory stackTraceFactory) {
        String stackTrace(UUID id, Instant timestamp, String ip, String address, int line) {
            return stackTraceFactory.create(id, timestamp, ip, address, line);
        }
    }

    @FunctionalInterface
    private interface StackTraceFactory {
        String create(UUID id, Instant timestamp, String ip, String address, int line);
    }
}
