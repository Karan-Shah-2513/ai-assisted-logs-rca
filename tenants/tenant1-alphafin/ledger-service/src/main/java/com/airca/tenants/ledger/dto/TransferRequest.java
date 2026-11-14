package com.airca.tenants.ledger.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TransferRequest {

    private String transferId;
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private String currency;
    private BigDecimal exchangeRateSpread;
    private BigDecimal spread;

    public TransferRequest() {
    }

    public TransferRequest(String transferId, BigDecimal amount, BigDecimal exchangeRateSpread) {
        this.transferId = transferId;
        this.amount = amount;
        this.exchangeRateSpread = exchangeRateSpread;
    }

    public BigDecimal resolveSpread() {
        if (exchangeRateSpread != null) {
            return exchangeRateSpread;
        }
        if (spread != null) {
            return spread;
        }
        return BigDecimal.ZERO;
    }

    public String getTransferId() {
        return transferId;
    }

    public void setTransferId(String transferId) {
        this.transferId = transferId;
    }

    public String getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(String fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public String getToAccountId() {
        return toAccountId;
    }

    public void setToAccountId(String toAccountId) {
        this.toAccountId = toAccountId;
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

    public BigDecimal getExchangeRateSpread() {
        return exchangeRateSpread;
    }

    public void setExchangeRateSpread(BigDecimal exchangeRateSpread) {
        this.exchangeRateSpread = exchangeRateSpread;
    }

    public BigDecimal getSpread() {
        return spread;
    }

    public void setSpread(BigDecimal spread) {
        this.spread = spread;
    }
}
