package com.airca.tenants.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.airca.tenants.common.advice.TenantGlobalExceptionHandler;
import com.airca.tenants.common.reporter.AircaLogReporter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "tenant.airca.enabled=true"
})
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AircaLogReporter aircaLogReporter;

    private String createJwt(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("mock-signature-bytes".getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + signature;
    }

    @Test
    @DisplayName("Health endpoint returns UP status and auth-service identifier")
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/api/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("auth-service"));

        mockMvc.perform(get("/api/v1/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("auth-service"));
    }

    @Test
    @DisplayName("POST /api/auth/token with valid, nonexpired JWT claim succeeds (200)")
    void testTokenWithValidNonExpiredClaimSucceeds() throws Exception {
        long futureExp = Instant.now().getEpochSecond() + 7200;
        String validClaims = "{\"sub\":\"user-betapay-1\",\"exp\":" + futureExp + ",\"role\":\"PAYMENT_ADMIN\"}";
        String jwt = createJwt(validClaims);

        String requestJson = """
                {
                    "token": "%s"
                }
                """.formatted(jwt);

        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.sub").value("user-betapay-1"))
                .andExpect(jsonPath("$.exp").value(futureExp))
                .andExpect(jsonPath("$.service").value("auth-service"));
    }

    @Test
    @DisplayName("POST /api/auth/token with valid raw JSON claims without exp succeeds (200)")
    void testTokenWithValidRawClaimsSucceeds() throws Exception {
        String requestJson = """
                {
                    "claims": "{\\"sub\\":\\"merchant-42\\",\\"role\\":\\"MERCHANT\\"}"
                }
                """;

        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.sub").value("merchant-42"));
    }

    @Test
    @DisplayName("POST /api/auth/token with expired exp claim raises IllegalArgumentException -> 500 and reporter trace")
    void testTokenWithExpiredClaimThrowsAndReports() throws Exception {
        long pastExp = Instant.now().getEpochSecond() - 3600;
        String expiredClaims = "{\"sub\":\"user-expired\",\"exp\":" + pastExp + "}";
        String jwt = createJwt(expiredClaims);

        String requestJson = """
                {
                    "token": "%s"
                }
                """.formatted(jwt);

        mockMvc.perform(post("/api/auth/token")
                        .header("X-Correlation-ID", "corr-token-expired-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TenantGlobalExceptionHandler.CORRELATION_HEADER, "corr-token-expired-1"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.correlationId").value("corr-token-expired-1"));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("auth-service"),
                stackTraceCaptor.capture(),
                eq("corr-token-expired-1")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("IllegalArgumentException")
                .contains("JWT token has expired")
                .contains("AuthController");
    }

    @Test
    @DisplayName("POST /api/auth/token with malformed Base64 claim data raises IllegalArgumentException -> 500 and reporter trace")
    void testTokenWithMalformedBase64ThrowsAndReports() throws Exception {
        String malformedJwt = "header.invalid base64 characters with spaces and symbols @#$%.signature";

        String requestJson = """
                {
                    "token": "%s"
                }
                """.formatted(malformedJwt);

        mockMvc.perform(post("/api/auth/token")
                        .header("X-Correlation-ID", "corr-token-malformed-base64")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TenantGlobalExceptionHandler.CORRELATION_HEADER, "corr-token-malformed-base64"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.correlationId").value("corr-token-malformed-base64"));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("auth-service"),
                stackTraceCaptor.capture(),
                eq("corr-token-malformed-base64")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("IllegalArgumentException")
                .contains("AuthController");
    }

    @Test
    @DisplayName("POST /api/auth/token with malformed segment count raises IllegalArgumentException -> 500")
    void testTokenWithMalformedSegmentsThrows500() throws Exception {
        String requestJson = """
                {
                    "token": "part1.part2.part3.part4.part5"
                }
                """;

        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    @DisplayName("POST /api/auth/token with malformed non-JSON payload raises IllegalArgumentException -> 500")
    void testTokenWithMalformedNonJsonPayloadThrows500() throws Exception {
        String nonJsonEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("malformed plain text { not json".getBytes(StandardCharsets.UTF_8));
        String jwt = "header." + nonJsonEncoded + ".signature";

        mockMvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + jwt + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    @DisplayName("POST /api/auth/token with malformed non-numeric exp claim raises NumberFormatException -> 500 and reporter trace")
    void testTokenWithMalformedExpDataThrowsAndReports() throws Exception {
        String malformedExpClaims = "{\"sub\":\"user-malformed\",\"exp\":\"not_a_numeric_timestamp\"}";
        String jwt = createJwt(malformedExpClaims);

        mockMvc.perform(post("/api/auth/token")
                        .header("X-Correlation-ID", "corr-token-malformed-exp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + jwt + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("auth-service"),
                stackTraceCaptor.capture(),
                eq("corr-token-malformed-exp")
        );

        String stackTrace = stackTraceCaptor.getValue();
        // NumberFormatException is a subclass of IllegalArgumentException
        assertThat(stackTrace)
                .contains("NumberFormatException")
                .contains("AuthController");
    }

    @Test
    @DisplayName("POST /api/auth/mfa with valid metadata and fingerprint succeeds (200)")
    void testMfaWithValidMetadataSucceeds() throws Exception {
        String requestJson = """
                {
                    "userId": "user-mfa-101",
                    "code": "789123",
                    "deviceMetadata": {
                        "fingerprint": "FP-BETAPAY-DEVICE-9000",
                        "deviceId": "dev-laptop-01",
                        "userAgent": "Mozilla/5.0 Chrome",
                        "ipAddress": "192.168.1.50"
                    }
                }
                """;

        mockMvc.perform(post("/api/auth/mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.mfaVerified").value(true))
                .andExpect(jsonPath("$.userId").value("user-mfa-101"))
                .andExpect(jsonPath("$.fingerprint").value("fp-betapay-device-9000"))
                .andExpect(jsonPath("$.service").value("auth-service"));
    }

    @Test
    @DisplayName("POST /api/auth/mfa with missing deviceMetadata.fingerprint triggers real NPE -> 500 and reporter trace")
    void testMfaMissingFingerprintTriggersNpeAndReports() throws Exception {
        String requestJson = """
                {
                    "userId": "user-mfa-missing-fp",
                    "code": "654321",
                    "deviceMetadata": {
                        "deviceId": "dev-phone-02",
                        "userAgent": "Mobile Safari"
                    }
                }
                """;

        mockMvc.perform(post("/api/auth/mfa")
                        .header("X-Correlation-ID", "corr-mfa-npe-missing-fp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TenantGlobalExceptionHandler.CORRELATION_HEADER, "corr-mfa-npe-missing-fp"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.correlationId").value("corr-mfa-npe-missing-fp"));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("auth-service"),
                stackTraceCaptor.capture(),
                eq("corr-mfa-npe-missing-fp")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("NullPointerException")
                .contains("AuthController.mfa");
    }

    @Test
    @DisplayName("POST /api/auth/mfa with null deviceMetadata triggers real NPE -> 500 and reporter trace")
    void testMfaNullDeviceMetadataTriggersNpeAndReports() throws Exception {
        String requestJson = """
                {
                    "userId": "user-mfa-null-metadata",
                    "code": "112233"
                }
                """;

        mockMvc.perform(post("/api/auth/mfa")
                        .header("X-Correlation-ID", "corr-mfa-npe-null-meta")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("auth-service"),
                stackTraceCaptor.capture(),
                eq("corr-mfa-npe-null-meta")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("NullPointerException")
                .contains("AuthController.mfa");
    }

    @Test
    @DisplayName("POST /api/v1/auth endpoints also support both token and mfa")
    void testV1EndpointsSupport() throws Exception {
        long futureExp = Instant.now().getEpochSecond() + 3600;
        String jwt = createJwt("{\"sub\":\"v1-user\",\"exp\":" + futureExp + "}");

        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + jwt + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sub").value("v1-user"));

        mockMvc.perform(post("/api/v1/auth/mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"v1-mfa\",\"deviceMetadata\":{\"fingerprint\":\"fp-v1-ok\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fingerprint").value("fp-v1-ok"));

        mockMvc.perform(post("/api/v1/auth/mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"v1-mfa-fail\"}"))
                .andExpect(status().isInternalServerError());
    }
}
