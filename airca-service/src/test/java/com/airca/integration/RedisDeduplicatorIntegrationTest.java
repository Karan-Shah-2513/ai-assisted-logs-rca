package com.airca.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.airca.deduplication.RedisDeduplicatorService;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisDeduplicatorIntegrationTest {

    private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisDeduplicatorService deduplicator;

    @BeforeAll
    static void connectToRedisContainer() {
        connectionFactory = new LettuceConnectionFactory("localhost", REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        deduplicator = new RedisDeduplicatorService(redisTemplate);
    }

    @AfterAll
    static void closeRedisConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void clearRedisDatabase() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void deduplicatesWithinTenantAndKeepsCountersIsolatedAcrossTenants() {
        var firstA = deduplicator.record(TENANT_A, "same-fingerprint");
        var secondA = deduplicator.record(TENANT_A, "same-fingerprint");
        var firstB = deduplicator.record(TENANT_B, "same-fingerprint");

        assertEquals(1, firstA.occurrenceCount());
        assertEquals(2, secondA.occurrenceCount());
        assertEquals(1, secondA.deduplicatedLogs());
        assertEquals(1, firstB.occurrenceCount());
        assertEquals(new RedisDeduplicatorService.DeduplicationStats(2, 1), deduplicator.getStats(TENANT_A));
        assertEquals(new RedisDeduplicatorService.DeduplicationStats(1, 0), deduplicator.getStats(TENANT_B));

        assertTtlExists(RedisDeduplicatorService.fingerprintKey(TENANT_A, "same-fingerprint"));
        assertTtlExists(RedisDeduplicatorService.totalStatsKey(TENANT_A));
        assertTtlExists(RedisDeduplicatorService.deduplicatedStatsKey(TENANT_A));
        assertTtlExists(RedisDeduplicatorService.fingerprintKey(TENANT_B, "same-fingerprint"));
    }

    @Test
    void assignsTtlToLegacyFingerprintKeyAndExpiresIt() throws InterruptedException {
        String key = RedisDeduplicatorService.fingerprintKey(TENANT_A, "legacy-fingerprint");
        redisTemplate.opsForValue().set(key, "5");

        var result = deduplicator.record(TENANT_A, "legacy-fingerprint");

        assertEquals(6, result.occurrenceCount());
        assertTtlExists(key);

        redisTemplate.expire(key, Duration.ofSeconds(1));
        Thread.sleep(1_100);

        assertEquals(1, deduplicator.record(TENANT_A, "legacy-fingerprint").occurrenceCount());
    }

    private void assertTtlExists(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= RedisDeduplicatorService.DEFAULT_TTL_MINUTES * 60,
                "Expected a positive TTL no greater than the configured default for " + key + ", got " + ttl);
    }
}
