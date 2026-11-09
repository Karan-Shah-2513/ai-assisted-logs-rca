package com.airca.tenants.auth.controller;

import com.airca.tenants.auth.dto.DeviceMetadata;
import com.airca.tenants.auth.dto.MfaRequest;
import com.airca.tenants.auth.dto.TokenRequest;
import com.airca.tenants.common.config.TenantAircaProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class AuthController {

    private final TenantAircaProperties properties;
    private final ObjectMapper objectMapper;

    public AuthController(TenantAircaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", properties.getServiceName(),
                "tenantId", properties.getTenantId(),
                "status", "UP"
        );
    }

    /**
     * Intentional defect / verification endpoint: parses JWT claim string(s) and naturally
     * raises IllegalArgumentException (or subclass such as NumberFormatException) for malformed claim data,
     * or an explicit application-level IllegalArgumentException for an expired exp claim.
     * Valid, nonexpired claims succeed.
     */
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> token(@RequestBody(required = false) TokenRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Token request body is required");
        }

        List<String> claimStrings = request.resolveClaimStrings();
        if (claimStrings.isEmpty()) {
            throw new IllegalArgumentException("JWT claim string(s) must be provided in request body");
        }

        List<Map<String, Object>> parsedClaims = new ArrayList<>();
        for (String claimStr : claimStrings) {
            parsedClaims.add(parseAndValidateClaimString(claimStr));
        }

        Map<String, Object> firstClaims = parsedClaims.get(0);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("valid", true);
        if (firstClaims.containsKey("sub")) {
            response.put("sub", firstClaims.get("sub"));
        }
        if (firstClaims.containsKey("exp")) {
            response.put("exp", firstClaims.get("exp"));
        }
        response.put("claims", parsedClaims.size() == 1 ? firstClaims : parsedClaims);
        response.put("service", properties.getServiceName());

        return ResponseEntity.ok(response);
    }

    /**
     * Intentional defect: missing deviceMetadata.fingerprint triggers a real NullPointerException
     * through an unguarded dereference/use of the fingerprint.
     * Valid metadata succeeds.
     */
    @PostMapping("/mfa")
    public ResponseEntity<Map<String, Object>> mfa(@RequestBody(required = false) MfaRequest request) {
        DeviceMetadata metadata = (request != null) ? request.resolveDeviceMetadata() : null;
        String fingerprint = (metadata != null) ? metadata.getFingerprint() : ((request != null) ? request.getFingerprint() : null);

        // Intentional defect: unguarded dereference/use of fingerprint triggers real NullPointerException if missing
        String normalizedFingerprint = fingerprint.trim().toLowerCase();

        String userId = (request != null && request.getUserId() != null)
                ? request.getUserId()
                : UUID.randomUUID().toString();

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "mfaVerified", true,
                "userId", userId,
                "fingerprint", normalizedFingerprint,
                "service", properties.getServiceName()
        ));
    }

    private Map<String, Object> parseAndValidateClaimString(String claimStr) {
        if (claimStr == null || claimStr.isBlank()) {
            throw new IllegalArgumentException("JWT claim string must not be null or blank");
        }

        String payloadJson;
        if (claimStr.contains(".")) {
            String[] parts = claimStr.split("\\.");
            if (parts.length < 2 || parts.length > 3) {
                throw new IllegalArgumentException(
                        "Malformed JWT claim data: expected 2 or 3 dot-separated segments, got " + parts.length);
            }
            // Base64URL decode payload segment (naturally throws IllegalArgumentException on illegal base64 chars)
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            payloadJson = new String(decoded, StandardCharsets.UTF_8);
        } else if (claimStr.trim().startsWith("{")) {
            // Raw JSON claim string
            payloadJson = claimStr.trim();
        } else {
            // Base64URL encoded claim string (naturally throws IllegalArgumentException on illegal base64 chars)
            byte[] decoded = Base64.getUrlDecoder().decode(claimStr.trim());
            payloadJson = new String(decoded, StandardCharsets.UTF_8);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed JWT claim JSON payload: " + e.getMessage(), e);
        }

        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Malformed JWT claim data: payload is not a JSON object");
        }

        if (root.has("exp") && !root.get("exp").isNull()) {
            JsonNode expNode = root.get("exp");
            long exp;
            if (expNode.isIntegralNumber()) {
                exp = expNode.asLong();
            } else if (expNode.isFloatingPointNumber()) {
                exp = (long) expNode.asDouble();
            } else if (expNode.isTextual()) {
                // If textual, Long.parseLong naturally throws NumberFormatException (subclass of IllegalArgumentException)
                exp = Long.parseLong(expNode.asText());
            } else {
                // Arrays/objects naturally throw NumberFormatException
                exp = Long.parseLong(expNode.asText());
            }

            long nowSeconds = Instant.now().getEpochSecond();
            long expSeconds = exp > 100_000_000_000L ? (exp / 1000L) : exp;
            if (expSeconds < nowSeconds) {
                throw new IllegalArgumentException(
                        "JWT token has expired: exp=" + exp + " is before current time=" + nowSeconds);
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.convertValue(root, Map.class);
        return map;
    }
}
