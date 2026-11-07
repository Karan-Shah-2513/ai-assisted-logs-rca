package com.airca.deduplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisDeduplicatorServiceTest {

    private final RedisDeduplicatorService service =
            new RedisDeduplicatorService(mock(StringRedisTemplate.class));

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
}
