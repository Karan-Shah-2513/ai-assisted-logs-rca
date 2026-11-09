package com.airca.tenants.ledger;

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
class LedgerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AircaLogReporter aircaLogReporter;

    @Test
    @DisplayName("Health endpoint returns UP status and ledger-service identifier")
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/api/ledger/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("ledger-service"));

        mockMvc.perform(get("/api/v1/ledger/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("ledger-service"));
    }

    @Test
    @DisplayName("POST /api/ledger/transfer with nonzero spread succeeds (200)")
    void testTransferWithNonZeroSpreadSucceeds() throws Exception {
        String requestJson = """
                {
                    "transferId": "TR-1001",
                    "fromAccountId": "ACC-SRC-01",
                    "toAccountId": "ACC-DST-02",
                    "amount": 1000.0,
                    "currency": "USD",
                    "exchangeRateSpread": 0.05
                }
                """;

        mockMvc.perform(post("/api/ledger/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.transferId").value("TR-1001"))
                .andExpect(jsonPath("$.fromAccountId").value("ACC-SRC-01"))
                .andExpect(jsonPath("$.toAccountId").value("ACC-DST-02"))
                .andExpect(jsonPath("$.amount").value(1000.0))
                .andExpect(jsonPath("$.exchangeRateSpread").value(0.05))
                .andExpect(jsonPath("$.service").value("ledger-service"));
    }

    @Test
    @DisplayName("POST /api/ledger/transfer with nonzero integer spread succeeds (200)")
    void testTransferWithNonZeroIntegerSpreadSucceeds() throws Exception {
        String requestJson = """
                {
                    "transferId": "TR-1002",
                    "amount": 500.0,
                    "exchangeRateSpread": 2
                }
                """;

        mockMvc.perform(post("/api/ledger/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.transferId").value("TR-1002"))
                .andExpect(jsonPath("$.exchangeRateSpread").value(2));
    }

    @Test
    @DisplayName("POST /api/ledger/transfer with zero spread triggers real ArithmeticException and returns 500 via advice")
    void testTransferWithZeroSpreadThrowsArithmeticExceptionAndReports500() throws Exception {
        String requestJson = """
                {
                    "transferId": "TR-ERR-01",
                    "fromAccountId": "ACC-SRC-03",
                    "toAccountId": "ACC-DST-04",
                    "amount": 2500.0,
                    "exchangeRateSpread": 0
                }
                """;

        mockMvc.perform(post("/api/ledger/transfer")
                        .header("X-Correlation-ID", "corr-transfer-zero-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TenantGlobalExceptionHandler.CORRELATION_HEADER, "corr-transfer-zero-1"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.correlationId").value("corr-transfer-zero-1"));

        // Verify exception propagation into shared RestControllerAdvice reports full stack trace to Airca
        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("ledger-service"),
                stackTraceCaptor.capture(),
                eq("corr-transfer-zero-1")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("ArithmeticException")
                .contains("/ by zero")
                .contains("LedgerController.transfer");
    }

    @Test
    @DisplayName("POST /api/ledger/transfer with 0.0 decimal spread triggers real ArithmeticException (500)")
    void testTransferWithZeroDecimalSpreadThrowsArithmeticException() throws Exception {
        String requestJson = """
                {
                    "transferId": "TR-ERR-02",
                    "amount": 100.0,
                    "exchangeRateSpread": 0.0
                }
                """;

        mockMvc.perform(post("/api/ledger/transfer")
                        .header("X-Correlation-ID", "corr-transfer-zero-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("ledger-service"),
                stackTraceCaptor.capture(),
                eq("corr-transfer-zero-2")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("ArithmeticException")
                .contains("LedgerController.transfer");
    }

    @Test
    @DisplayName("POST /api/ledger/transfer with empty body triggers division by zero (500)")
    void testTransferWithEmptyBodyReturns500() throws Exception {
        mockMvc.perform(post("/api/ledger/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    @DisplayName("POST /api/ledger/reconcile with balanced debit/credit entries succeeds (200)")
    void testReconcileBalancedEntriesSucceeds() throws Exception {
        String requestJson = """
                {
                    "reconciliationId": "REC-BAL-01",
                    "entries": [
                        {"entryId": "E1", "type": "DEBIT", "amount": 150.0},
                        {"entryId": "E2", "type": "CREDIT", "amount": 150.0}
                    ]
                }
                """;

        mockMvc.perform(post("/api/ledger/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.reconciliationId").value("REC-BAL-01"))
                .andExpect(jsonPath("$.totalDebits").value(150.0))
                .andExpect(jsonPath("$.totalCredits").value(150.0))
                .andExpect(jsonPath("$.service").value("ledger-service"));
    }

    @Test
    @DisplayName("POST /api/ledger/reconcile with balanced direct totals succeeds (200)")
    void testReconcileBalancedDirectTotalsSucceeds() throws Exception {
        String requestJson = """
                {
                    "reconciliationId": "REC-BAL-02",
                    "totalDebit": 500.0,
                    "totalCredit": 500.0
                }
                """;

        mockMvc.perform(post("/api/ledger/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.totalDebits").value(500.0))
                .andExpect(jsonPath("$.totalCredits").value(500.0));
    }

    @Test
    @DisplayName("POST /api/ledger/reconcile with unbalanced entries triggers real IllegalStateException (500)")
    void testReconcileUnbalancedEntriesThrowsIllegalStateExceptionAndReports500() throws Exception {
        String requestJson = """
                {
                    "reconciliationId": "REC-UNBAL-01",
                    "entries": [
                        {"entryId": "E1", "type": "DEBIT", "amount": 300.0},
                        {"entryId": "E2", "type": "CREDIT", "amount": 150.0}
                    ]
                }
                """;

        mockMvc.perform(post("/api/ledger/reconcile")
                        .header("X-Correlation-ID", "corr-reconcile-unbal-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TenantGlobalExceptionHandler.CORRELATION_HEADER, "corr-reconcile-unbal-1"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.correlationId").value("corr-reconcile-unbal-1"));

        // Verify exception propagation into shared RestControllerAdvice reports full stack trace to Airca
        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("ledger-service"),
                stackTraceCaptor.capture(),
                eq("corr-reconcile-unbal-1")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("IllegalStateException")
                .contains("lock contention")
                .contains("LedgerController.reconcile");
    }

    @Test
    @DisplayName("POST /api/ledger/reconcile with unbalanced direct totals triggers real IllegalStateException (500)")
    void testReconcileUnbalancedDirectTotalsThrowsIllegalStateException() throws Exception {
        String requestJson = """
                {
                    "reconciliationId": "REC-UNBAL-02",
                    "totalDebit": 1000.0,
                    "totalCredit": 850.0
                }
                """;

        mockMvc.perform(post("/api/ledger/reconcile")
                        .header("X-Correlation-ID", "corr-reconcile-unbal-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("ledger-service"),
                stackTraceCaptor.capture(),
                eq("corr-reconcile-unbal-2")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("IllegalStateException")
                .contains("LedgerController.reconcile");
    }

    @Test
    @DisplayName("POST /api/ledger/reconcile with raw JSON array format handles balanced -> 200 and unbalanced -> 500")
    void testReconcileWithRawJsonArray() throws Exception {
        String balancedArray = """
                [
                    {"type": "DEBIT", "amount": 100.0},
                    {"type": "CREDIT", "amount": 100.0}
                ]
                """;
        mockMvc.perform(post("/api/ledger/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(balancedArray))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.balanced").value(true));

        String unbalancedArray = """
                [
                    {"type": "DEBIT", "amount": 100.0},
                    {"type": "CREDIT", "amount": 75.0}
                ]
                """;
        mockMvc.perform(post("/api/ledger/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unbalancedArray))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    @DisplayName("POST /api/v1/ledger paths are also supported for both transfer and reconcile")
    void testV1EndpointsSupport() throws Exception {
        mockMvc.perform(post("/api/v1/ledger/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferId\":\"v1-ok\",\"exchangeRateSpread\":0.02}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(post("/api/v1/ledger/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transferId\":\"v1-fail\",\"exchangeRateSpread\":0}"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(post("/api/v1/ledger/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalDebit\":50.0,\"totalCredit\":50.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(post("/api/v1/ledger/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalDebit\":50.0,\"totalCredit\":40.0}"))
                .andExpect(status().isInternalServerError());
    }
}
