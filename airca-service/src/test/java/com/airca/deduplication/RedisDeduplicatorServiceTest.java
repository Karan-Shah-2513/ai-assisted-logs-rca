package com.airca.deduplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisDeduplicatorServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisDeduplicatorService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new RedisDeduplicatorService(redisTemplate);
    }

    @Test
    void fingerprintsMatchWhenOnlyVolatileLogValuesDiffer() {
        String first = "ERROR request 550e8400-e29b-41d4-a716-446655440000 "
                + "at 2025-11-07T17:29:13Z from 10.1.2.3 via 2001:db8::1 "
                + "in Foo.java:123 at 0x7ffe12345678";
        String second = "ERROR request 550e8400-e29b-41d4-a716-446655440001 "
                + "at 2025-11-08T17:30:14Z from 10.4.5.6 via 2001:db8::2 "
                + "in Foo.java:456 at 0x7ffeabcdef12";

        assertEquals(service.fingerprint(first), service.fingerprint(second));
        assertTrue(service.fingerprint(first).matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> service.fingerprint(" \n "));
    }

    @Test
    void defaultConfigurationUsesSixtyMinutesAndThirtySixHundredSecondsTtl() {
        assertEquals(60L, service.getTtlMinutes());
        assertEquals(3600L, service.getTtlSeconds());
        assertNotNull(service.getIncrementScript());
    }

    @Test
    void luaScriptContainsAtomicIncrementAndTtlRepairLogic() {
        String script = RedisDeduplicatorService.INCREMENT_WITH_TTL_LUA;
        assertTrue(script.contains("redis.call('INCR', KEYS[1])"), "Lua script must atomically INCR KEYS[1]");
        assertTrue(script.contains("current == 1"), "Lua script must check if count is 1");
        assertTrue(script.contains("TTL"), "Lua script must check TTL for legacy keys");
        assertTrue(script.contains("redis.call('EXPIRE', KEYS[1], ARGV[1])"), "Lua script must call EXPIRE with TTL");
        assertTrue(script.contains("return current"), "Lua script must return incremented count");
        assertEquals(Long.class, RedisDeduplicatorService.INCREMENT_SCRIPT.getResultType());
    }

    @Test
    void recordFirstOccurrenceExecutesLuaWithExactKeyFormatAndDefaultTtl() {
        String tenantId = "11111111-1111-1111-1111-111111111111";
        String hash = "c5b2a3d4e5f6";
        String expectedIncidentKey = "tenant:" + tenantId + ":incident:" + hash;
        String expectedTotalKey = "tenant:" + tenantId + ":stats:total";
        String deduplicatedKey = "tenant:" + tenantId + ":stats:deduplicated";

        when(redisTemplate.execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(expectedIncidentKey)),
                eq("3600"))).thenReturn(1L);

        when(redisTemplate.execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(expectedTotalKey)),
                eq("3600"))).thenReturn(1L);

        RedisDeduplicatorService.DeduplicationResult result = service.record(tenantId, hash);

        assertEquals(hash, result.fingerprint());
        assertEquals(1L, result.occurrenceCount());
        assertEquals(0L, result.deduplicatedLogs());
        assertEquals(1L, result.totalLogs());

        verify(redisTemplate).execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(expectedIncidentKey)),
                eq("3600"));
        verify(redisTemplate).execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(expectedTotalKey)),
                eq("3600"));
        verify(redisTemplate, never()).execute(
                any(),
                eq(List.of(deduplicatedKey)),
                any());
    }

    @Test
    void recordDuplicateOccurrenceIncrementsDeduplicatedCounterWithTtl() {
        String tenantId = "11111111-1111-1111-1111-111111111111";
        String hash = "duplicateHash789";
        String incidentKey = "tenant:" + tenantId + ":incident:" + hash;
        String totalKey = "tenant:" + tenantId + ":stats:total";
        String deduplicatedKey = "tenant:" + tenantId + ":stats:deduplicated";

        when(redisTemplate.execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(incidentKey)),
                eq("3600"))).thenReturn(2L);

        when(redisTemplate.execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(totalKey)),
                eq("3600"))).thenReturn(10L);

        when(redisTemplate.execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(deduplicatedKey)),
                eq("3600"))).thenReturn(5L);

        RedisDeduplicatorService.DeduplicationResult result = service.record(tenantId, hash);

        assertEquals(hash, result.fingerprint());
        assertEquals(2L, result.occurrenceCount());
        assertEquals(5L, result.deduplicatedLogs());
        assertEquals(10L, result.totalLogs());

        verify(redisTemplate).execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(incidentKey)),
                eq("3600"));
        verify(redisTemplate).execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(totalKey)),
                eq("3600"));
        verify(redisTemplate).execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(deduplicatedKey)),
                eq("3600"));
    }

    @Test
    void customTtlConfigurationPropagatesToLuaExecution() {
        RedisDeduplicatorService customService = new RedisDeduplicatorService(redisTemplate, 30L);
        assertEquals(30L, customService.getTtlMinutes());
        assertEquals(1800L, customService.getTtlSeconds());

        String tenantId = "22222222-2222-2222-2222-222222222222";
        String hash = "hash30min";
        String incidentKey = "tenant:" + tenantId + ":incident:" + hash;
        String totalKey = "tenant:" + tenantId + ":stats:total";

        when(redisTemplate.execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(incidentKey)),
                eq("1800"))).thenReturn(1L);

        when(redisTemplate.execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(totalKey)),
                eq("1800"))).thenReturn(1L);

        RedisDeduplicatorService.DeduplicationResult result = customService.record(tenantId, hash);
        assertEquals(1L, result.occurrenceCount());

        verify(redisTemplate).execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(incidentKey)),
                eq("1800"));
        verify(redisTemplate).execute(
                eq(RedisDeduplicatorService.INCREMENT_SCRIPT),
                eq(List.of(totalKey)),
                eq("1800"));
    }

    @Test
    void getStatsReadsTenantScopedCounters() {
        String tenantId = "11111111-1111-1111-1111-111111111111";
        String totalKey = "tenant:" + tenantId + ":stats:total";
        String deduplicatedKey = "tenant:" + tenantId + ":stats:deduplicated";

        when(valueOperations.get(totalKey)).thenReturn("500");
        when(valueOperations.get(deduplicatedKey)).thenReturn("490");

        RedisDeduplicatorService.DeduplicationStats stats = service.getStats(tenantId);

        assertEquals(500L, stats.totalLogs());
        assertEquals(490L, stats.deduplicatedLogs());

        verify(valueOperations).get(totalKey);
        verify(valueOperations).get(deduplicatedKey);
    }

    @Test
    void getStatsReturnsZeroWhenCountersMissing() {
        String tenantId = "11111111-1111-1111-1111-111111111111";
        when(valueOperations.get(any())).thenReturn(null);

        RedisDeduplicatorService.DeduplicationStats stats = service.getStats(tenantId);

        assertEquals(0L, stats.totalLogs());
        assertEquals(0L, stats.deduplicatedLogs());
    }

    @Test
    void throwsExplicitExceptionWhenRedisScriptReturnsNull() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.record("tenant-1", "hash-1"));

        assertTrue(ex.getMessage().contains("Redis did not return a valid incremented counter"));
    }

    @Test
    void throwsExplicitExceptionWhenRedisScriptReturnsInvalidNumber() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(0L);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.record("tenant-1", "hash-1"));

        assertTrue(ex.getMessage().contains("Redis did not return a valid incremented counter"));
    }

    @Test
    void throwsExplicitExceptionWhenCounterValueIsMalformed() {
        when(valueOperations.get(any())).thenReturn("corrupted-not-a-number");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.getStats("tenant-1"));

        assertTrue(ex.getMessage().contains("Invalid Redis counter value"));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> service.record(null, "hash"));
        assertThrows(IllegalArgumentException.class, () -> service.record("   ", "hash"));
        assertThrows(IllegalArgumentException.class, () -> service.record("tenant-1", null));
        assertThrows(IllegalArgumentException.class, () -> service.record("tenant-1", "  "));
        assertThrows(IllegalArgumentException.class, () -> service.getStats(null));
        assertThrows(IllegalArgumentException.class, () -> service.getStats("  "));
        assertThrows(IllegalArgumentException.class, () -> new RedisDeduplicatorService(redisTemplate, 0L));
        assertThrows(IllegalArgumentException.class, () -> new RedisDeduplicatorService(redisTemplate, -10L));
    }
}
