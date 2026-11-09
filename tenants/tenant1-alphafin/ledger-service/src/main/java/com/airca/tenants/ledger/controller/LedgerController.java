package com.airca.tenants.ledger.controller;

import com.airca.tenants.common.config.TenantAircaProperties;
import com.airca.tenants.ledger.dto.ReconcileRequest;
import com.airca.tenants.ledger.dto.TransferRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/ledger", "/api/v1/ledger"})
public class LedgerController {

    private final TenantAircaProperties properties;

    public LedgerController(TenantAircaProperties properties) {
        this.properties = properties;
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
     * Intentional defect: transfer calculates exchange rate spread factor using real arithmetic;
     * when exchangeRateSpread is exactly 0, integer division by zero throws real ArithmeticException.
     * Nonzero spread values succeed normally.
     */
    @PostMapping("/transfer")
    public ResponseEntity<Map<String, Object>> transfer(@RequestBody(required = false) TransferRequest request) {
        BigDecimal spread = (request != null) ? request.resolveSpread() : BigDecimal.ZERO;

        // Intentional defect: real arithmetic division by zero when spread is exactly 0
        if (spread == null || spread.compareTo(BigDecimal.ZERO) == 0) {
            long zeroDivisor = (spread != null) ? spread.longValue() : 0L;
            long rateFactor = 10000L / zeroDivisor; // Throws real java.lang.ArithmeticException: / by zero
        }

        BigDecimal amount = (request != null && request.getAmount() != null)
                ? request.getAmount()
                : BigDecimal.valueOf(100.0);

        BigDecimal fee = amount.multiply(spread).setScale(4, RoundingMode.HALF_UP);
        String transferId = (request != null && request.getTransferId() != null)
                ? request.getTransferId()
                : UUID.randomUUID().toString();

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "transferId", transferId,
                "fromAccountId", (request != null && request.getFromAccountId() != null) ? request.getFromAccountId() : "ACC-SRC",
                "toAccountId", (request != null && request.getToAccountId() != null) ? request.getToAccountId() : "ACC-DST",
                "amount", amount,
                "exchangeRateSpread", spread,
                "fee", fee,
                "service", properties.getServiceName()
        ));
    }

    /**
     * Intentional defect: reconciliation verifies balanced debit and credit totals.
     * Balanced entries succeed normally. Unbalanced entries trigger a real IllegalStateException
     * representing simulated lock contention on the ledger partition.
     */
    @PostMapping("/reconcile")
    public ResponseEntity<Map<String, Object>> reconcile(@RequestBody(required = false) ReconcileRequest request) {
        BigDecimal totalDebits = (request != null) ? request.resolveTotalDebits() : BigDecimal.ZERO;
        BigDecimal totalCredits = (request != null) ? request.resolveTotalCredits() : BigDecimal.ZERO;

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new IllegalStateException(
                    "Simulated lock contention on ledger partition: unbalanced debit/credit entries (debits="
                    + totalDebits + ", credits=" + totalCredits + ", delta=" + totalDebits.subtract(totalCredits) + ")"
            );
        }

        String reconciliationId = (request != null && request.getReconciliationId() != null)
                ? request.getReconciliationId()
                : UUID.randomUUID().toString();

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "balanced", true,
                "reconciliationId", reconciliationId,
                "totalDebits", totalDebits,
                "totalCredits", totalCredits,
                "service", properties.getServiceName()
        ));
    }
}
