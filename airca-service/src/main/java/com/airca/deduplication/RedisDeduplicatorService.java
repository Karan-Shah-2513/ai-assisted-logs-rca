package com.airca.deduplication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisDeduplicatorService {

    public static final String INCREMENT_WITH_TTL_LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 or redis.call('TTL', KEYS[1]) == -1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """;

    public static final RedisScript<Long> INCREMENT_SCRIPT =
            new DefaultRedisScript<>(INCREMENT_WITH_TTL_LUA, Long.class);

    public static final long DEFAULT_TTL_MINUTES = 60L;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern ISO_TIMESTAMP_PATTERN = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:[Zz]|[+-]\\d{2}:?\\d{2})?\\b");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])");
    private static final Pattern LINE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(\\.java:|\\.kt:|\\.scala:|\\bline\\s+|\\bline=)\\d+");
    private static final Pattern MEMORY_ADDRESS_PATTERN = Pattern.compile(
            "(?i)\\b0x[0-9a-f]{6,}\\b");

    private final StringRedisTemplate redisTemplate;
    private final long ttlMinutes;
    private final long ttlSeconds;

    @Autowired
    public RedisDeduplicatorService(
            StringRedisTemplate redisTemplate,
            @Value("${app.retention.redis-dedup-ttl-minutes:60}") long ttlMinutes) {
        if (ttlMinutes <= 0) {
            throw new IllegalArgumentException("TTL minutes must be greater than 0");
        }
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "StringRedisTemplate must not be null");
        this.ttlMinutes = ttlMinutes;
        this.ttlSeconds = ttlMinutes * 60;
    }

    public RedisDeduplicatorService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_TTL_MINUTES);
    }

    public static String fingerprintKey(String tenantId, String hash) {
        return "tenant:" + tenantId + ":incident:" + hash;
    }

    public static String totalStatsKey(String tenantId) {
        return "tenant:" + tenantId + ":stats:total";
    }

    public static String deduplicatedStatsKey(String tenantId) {
        return "tenant:" + tenantId + ":stats:deduplicated";
    }

    public static String statsTotalKey(String tenantId) {
        return totalStatsKey(tenantId);
    }

    public static String statsDeduplicatedKey(String tenantId) {
        return deduplicatedStatsKey(tenantId);
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public RedisScript<Long> getIncrementScript() {
        return INCREMENT_SCRIPT;
    }

    public String fingerprint(String stackTrace) {
        return sha256(normalize(stackTrace));
    }

    public DeduplicationResult record(String tenantId, String fingerprint) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID must not be blank");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("Fingerprint must not be blank");
        }
        long occurrenceCount = increment(fingerprintKey(tenantId, fingerprint));
        long totalLogs = increment(totalStatsKey(tenantId));
        long duplicateLogs = 0;
        if (occurrenceCount > 1) {
            duplicateLogs = increment(deduplicatedStatsKey(tenantId));
        }
        return new DeduplicationResult(
                fingerprint,
                occurrenceCount,
                duplicateLogs,
                totalLogs);
    }

    public DeduplicationStats getStats(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID must not be blank");
        }
        return new DeduplicationStats(
                readCounter(totalStatsKey(tenantId)),
                readCounter(deduplicatedStatsKey(tenantId)));
    }

    private long readCounter(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalStateException("Invalid negative Redis counter value for " + key + ": " + parsed);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid Redis counter value for " + key, exception);
        }
    }

    private long increment(String key) {
        Long value = redisTemplate.execute(
                INCREMENT_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(ttlSeconds));
        if (value == null || value < 1) {
            throw new IllegalStateException("Redis did not return a valid incremented counter for " + key + ": " + value);
        }
        return value;
    }

    public static String normalize(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            throw new IllegalArgumentException("Stack trace must not be blank");
        }
        String normalized = UUID_PATTERN.matcher(stackTrace).replaceAll("<uuid>");
        normalized = ISO_TIMESTAMP_PATTERN.matcher(normalized).replaceAll("<timestamp>");
        normalized = IPV4_PATTERN.matcher(normalized).replaceAll("<ip>");
        normalized = IPV6_PATTERN.matcher(normalized).replaceAll("<ip>");
        normalized = LINE_NUMBER_PATTERN.matcher(normalized).replaceAll("$1<line>");
        normalized = MEMORY_ADDRESS_PATTERN.matcher(normalized).replaceAll("<address>");
        return normalized.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record DeduplicationResult(
            String fingerprint, long occurrenceCount, long deduplicatedLogs, long totalLogs) {
    }

    public record DeduplicationStats(long totalLogs, long deduplicatedLogs) {
    }
}
