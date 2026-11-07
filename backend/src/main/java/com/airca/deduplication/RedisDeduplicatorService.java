package com.airca.deduplication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisDeduplicatorService {

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

    public RedisDeduplicatorService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String fingerprint(String stackTrace) {
        return sha256(normalize(stackTrace));
    }

    public DeduplicationResult record(String tenantId, String fingerprint) {
        String prefix = "airca:tenant:" + tenantId + ":";
        long occurrenceCount = increment(prefix + "fingerprint:" + fingerprint);
        long totalLogs = increment(prefix + "stats:total");
        long duplicateLogs = 0;
        if (occurrenceCount > 1) {
            duplicateLogs = increment(prefix + "stats:deduplicated");
        }
        return new DeduplicationResult(
                fingerprint,
                occurrenceCount,
                duplicateLogs,
                totalLogs);
    }

    public DeduplicationStats getStats(String tenantId) {
        String prefix = "airca:tenant:" + tenantId + ":stats:";
        return new DeduplicationStats(
                readCounter(prefix + "total"),
                readCounter(prefix + "deduplicated"));
    }

    private long readCounter(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid Redis counter value for " + key, exception);
        }
    }

    private long increment(String key) {
        Long value = redisTemplate.opsForValue().increment(key);
        if (value == null) {
            throw new IllegalStateException("Redis did not return an incremented counter for " + key);
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
