package com.airca.tenants.wallet;

import static org.assertj.core.api.Assertions.assertThat;
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
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AircaLogReporter aircaLogReporter;

    @Test
    @DisplayName("Health endpoint returns UP status and wallet-service identifier")
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/api/wallet/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("wallet-service"));

        mockMvc.perform(get("/api/v1/wallet/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("wallet-service"));
    }

    @Test
    @DisplayName("POST /api/wallet/withdraw within threshold succeeds (200)")
    void testWithdrawWithinThresholdSucceeds() throws Exception {
        String requestJson = """
                {
                    "withdrawalId": "W-1001",
                    "walletId": "WAL-USER-01",
                    "amount": 500.0,
                    "currency": "USD"
                }
                """;

        mockMvc.perform(post("/api/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.withdrawalId").value("W-1001"))
                .andExpect(jsonPath("$.walletId").value("WAL-USER-01"))
                .andExpect(jsonPath("$.amount").value(500.0))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.service").value("wallet-service"));
    }

    @Test
    @DisplayName("POST /api/wallet/withdraw exactly at default liquidity threshold (10000.0) succeeds (200)")
    void testWithdrawAtThresholdSucceeds() throws Exception {
        String requestJson = """
                {
                    "withdrawalId": "W-1002",
                    "walletId": "WAL-USER-02",
                    "amount": 10000.0,
                    "currency": "USD"
                }
                """;

        mockMvc.perform(post("/api/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(10000.0));
    }

    @Test
    @DisplayName("POST /api/wallet/withdraw with custom threshold within limit succeeds (200)")
    void testWithdrawWithCustomThresholdWithinLimitSucceeds() throws Exception {
        String requestJson = """
                {
                    "withdrawalId": "W-1003",
                    "amount": 750.0,
                    "liquidityThreshold": 2000.0
                }
                """;

        mockMvc.perform(post("/api/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(750.0));
    }

    @Test
    @DisplayName("POST /api/wallet/withdraw with default amount (empty body) succeeds (200)")
    void testWithdrawEmptyBodySucceeds() throws Exception {
        mockMvc.perform(post("/api/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(50.0));
    }

    @Test
    @DisplayName("POST /api/wallet/withdraw exceeding default threshold triggers IllegalStateException and reports full stack trace (500)")
    void testWithdrawOverThresholdThrowsIllegalStateExceptionAndReports() throws Exception {
        String requestJson = """
                {
                    "withdrawalId": "W-FAIL-01",
                    "walletId": "WAL-USER-99",
                    "amount": 25000.0,
                    "currency": "USD"
                }
                """;

        mockMvc.perform(post("/api/wallet/withdraw")
                        .header("X-Correlation-ID", "corr-withdraw-over-threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TenantGlobalExceptionHandler.CORRELATION_HEADER, "corr-withdraw-over-threshold"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.correlationId").value("corr-withdraw-over-threshold"));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("wallet-service"),
                stackTraceCaptor.capture(),
                eq("corr-withdraw-over-threshold")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("IllegalStateException")
                .contains("Liquidity lock timeout")
                .contains("exceeds available liquidity threshold")
                .contains("connection timeout")
                .contains("WalletController.withdraw");
    }

    @Test
    @DisplayName("POST /api/wallet/withdraw exceeding custom threshold triggers IllegalStateException (500)")
    void testWithdrawOverCustomThresholdThrowsIllegalStateException() throws Exception {
        String requestJson = """
                {
                    "withdrawalId": "W-FAIL-02",
                    "amount": 1500.0,
                    "threshold": 1000.0
                }
                """;

        mockMvc.perform(post("/api/wallet/withdraw")
                        .header("X-Correlation-ID", "corr-withdraw-custom-threshold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("wallet-service"),
                stackTraceCaptor.capture(),
                eq("corr-withdraw-custom-threshold")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("IllegalStateException")
                .contains("Liquidity lock timeout")
                .contains("WalletController.withdraw");
    }

    @Test
    @DisplayName("POST /api/wallet/fx-quote with valid currency pair succeeds (200)")
    void testFxQuoteWithValidPairSucceeds() throws Exception {
        String requestJson = """
                {
                    "quoteId": "Q-1001",
                    "currencyPair": "EUR/USD",
                    "amount": 1000.0
                }
                """;

        mockMvc.perform(post("/api/wallet/fx-quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.quoteId").value("Q-1001"))
                .andExpect(jsonPath("$.currencyPair").value("EUR/USD"))
                .andExpect(jsonPath("$.fromCurrency").value("EUR"))
                .andExpect(jsonPath("$.toCurrency").value("USD"))
                .andExpect(jsonPath("$.rate").value(1.0850))
                .andExpect(jsonPath("$.amount").value(1000.0))
                .andExpect(jsonPath("$.convertedAmount").value(1085.0))
                .andExpect(jsonPath("$.service").value("wallet-service"));
    }

    @Test
    @DisplayName("POST /api/wallet/fx-quote with pair alias (USD/EUR) succeeds (200)")
    void testFxQuoteWithUsdEurSucceeds() throws Exception {
        String requestJson = """
                {
                    "pair": "USD/EUR",
                    "amount": 100.0
                }
                """;

        mockMvc.perform(post("/api/wallet/fx-quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.rate").value(0.9215))
                .andExpect(jsonPath("$.convertedAmount").value(92.15));
    }

    @Test
    @DisplayName("POST /api/wallet/fx-quote with fromCurrency and toCurrency succeeds (200)")
    void testFxQuoteWithFromAndToCurrenciesSucceeds() throws Exception {
        String requestJson = """
                {
                    "fromCurrency": "GBP",
                    "toCurrency": "USD",
                    "amount": 200.0
                }
                """;

        mockMvc.perform(post("/api/wallet/fx-quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.fromCurrency").value("GBP"))
                .andExpect(jsonPath("$.toCurrency").value("USD"))
                .andExpect(jsonPath("$.rate").value(1.2650))
                .andExpect(jsonPath("$.convertedAmount").value(253.0));
    }

    @Test
    @DisplayName("POST /api/wallet/fx-quote with custom rate in pair succeeds (200)")
    void testFxQuoteWithCustomRateInPairSucceeds() throws Exception {
        String requestJson = """
                {
                    "currencyPair": "EUR/USD:1.1500",
                    "amount": 100.0
                }
                """;

        mockMvc.perform(post("/api/wallet/fx-quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.rate").value(1.1500))
                .andExpect(jsonPath("$.convertedAmount").value(115.0));
    }

    @Test
    @DisplayName("POST /api/wallet/fx-quote with malformed currency pair causes real NumberFormatException and reports stack trace (500)")
    void testFxQuoteMalformedPairThrowsNumberFormatExceptionAndReports() throws Exception {
        String requestJson = """
                {
                    "currencyPair": "INVALID_PAIR",
                    "amount": 100.0
                }
                """;

        mockMvc.perform(post("/api/wallet/fx-quote")
                        .header("X-Correlation-ID", "corr-fx-malformed-pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TenantGlobalExceptionHandler.CORRELATION_HEADER, "corr-fx-malformed-pair"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.correlationId").value("corr-fx-malformed-pair"));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("wallet-service"),
                stackTraceCaptor.capture(),
                eq("corr-fx-malformed-pair")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("NumberFormatException")
                .contains("WalletController");
    }

    @Test
    @DisplayName("POST /api/wallet/fx-quote with invalid custom rate in pair causes real NumberFormatException (500)")
    void testFxQuoteInvalidRateInPairThrowsNumberFormatException() throws Exception {
        String requestJson = """
                {
                    "currencyPair": "EUR/USD:not_a_number",
                    "amount": 100.0
                }
                """;

        mockMvc.perform(post("/api/wallet/fx-quote")
                        .header("X-Correlation-ID", "corr-fx-invalid-rate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("wallet-service"),
                stackTraceCaptor.capture(),
                eq("corr-fx-invalid-rate")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("NumberFormatException")
                .contains("WalletController");
    }

    @Test
    @DisplayName("POST /api/wallet/fx-quote with malformed 3-segment pair causes real NumberFormatException (500)")
    void testFxQuoteMalformedThreeSegmentThrowsNumberFormatException() throws Exception {
        String requestJson = """
                {
                    "currencyPair": "EUR/USD/invalid_rate",
                    "amount": 100.0
                }
                """;

        mockMvc.perform(post("/api/wallet/fx-quote")
                        .header("X-Correlation-ID", "corr-fx-3seg-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("wallet-service"),
                stackTraceCaptor.capture(),
                eq("corr-fx-3seg-invalid")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("NumberFormatException")
                .contains("WalletController");
    }

    @Test
    @DisplayName("POST /api/wallet/fx-quote with empty/missing pair causes real NumberFormatException (500)")
    void testFxQuoteEmptyPairThrowsNumberFormatException() throws Exception {
        mockMvc.perform(post("/api/wallet/fx-quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyPair\": \"\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        mockMvc.perform(post("/api/wallet/fx-quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    @DisplayName("POST /api/v1/wallet endpoints also support both withdraw and fx-quote")
    void testV1EndpointsSupport() throws Exception {
        mockMvc.perform(post("/api/v1/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 200.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(post("/api/v1/wallet/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 50000.0}"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(post("/api/v1/wallet/fx-quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyPair\": \"EUR/USD\", \"amount\": 50.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(post("/api/v1/wallet/fx-quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyPair\": \"MALFORMED_INPUT\"}"))
                .andExpect(status().isInternalServerError());
    }
}
