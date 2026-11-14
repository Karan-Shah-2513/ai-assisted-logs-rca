package com.airca.ingestion;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LogSanitizer {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:[Zz]|[+-]\\d{2}:?\\d{2})?\\b");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])");
    private static final Pattern MEMORY_ADDRESS_PATTERN = Pattern.compile(
            "(?i)\\b0x[0-9a-f]{6,}\\b");
    private static final Pattern LINE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(\\.java:|\\.kt:|\\.scala:|\\bline\\s+|\\bline=)\\d+");

    public String sanitize(String stackTrace) {
        String sanitized = UUID_PATTERN.matcher(stackTrace).replaceAll("<uuid>");
        sanitized = TIMESTAMP_PATTERN.matcher(sanitized).replaceAll("<timestamp>");
        sanitized = IPV4_PATTERN.matcher(sanitized).replaceAll("<ip>");
        sanitized = IPV6_PATTERN.matcher(sanitized).replaceAll("<ip>");
        sanitized = MEMORY_ADDRESS_PATTERN.matcher(sanitized).replaceAll("<address>");
        return LINE_NUMBER_PATTERN.matcher(sanitized).replaceAll("$1<line>");
    }
}
