package com.airca.tenants.wallet.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WithdrawRequest {

    private String withdrawalId;
    private String walletId;
    private String accountId;
    private BigDecimal amount;
    private String currency;
    private BigDecimal liquidityThreshold;
    private BigDecimal threshold;

    public WithdrawRequest() {
    }

    public WithdrawRequest(String walletId, BigDecimal amount) {
        this.walletId = walletId;
        this.amount = amount;
    }

    public WithdrawRequest(String walletId, BigDecimal amount, BigDecimal liquidityThreshold) {
        this.walletId = walletId;
        this.amount = amount;
        this.liquidityThreshold = liquidityThreshold;
    }

    public String resolveWalletId() {
        if (walletId != null && !walletId.isBlank()) {
            return walletId.trim();
        }
        if (accountId != null && !accountId.isBlank()) {
            return accountId.trim();
        }
        return "WALLET-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public BigDecimal resolveThreshold() {
        if (liquidityThreshold != null) {
            return liquidityThreshold;
        }
        if (threshold != null) {
            return threshold;
        }
        return null;
    }

    public String getWithdrawalId() {
        return withdrawalId;
    }

    public void setWithdrawalId(String withdrawalId) {
        this.withdrawalId = withdrawalId;
    }

    public String getWalletId() {
        return walletId;
    }

    public void setWalletId(String walletId) {
        this.walletId = walletId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getLiquidityThreshold() {
        return liquidityThreshold;
    }

    public void setLiquidityThreshold(BigDecimal liquidityThreshold) {
        this.liquidityThreshold = liquidityThreshold;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }
}
