package com.airca.tenants.ledger.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReconcileEntry {

    private String entryId;
    private String accountId;
    private String type; // DEBIT or CREDIT
    private BigDecimal amount;
    private BigDecimal debit;
    private BigDecimal credit;

    public ReconcileEntry() {
    }

    public ReconcileEntry(String type, BigDecimal amount) {
        this.type = type;
        this.amount = amount;
    }

    public ReconcileEntry(String accountId, BigDecimal debit, BigDecimal credit) {
        this.accountId = accountId;
        this.debit = debit;
        this.credit = credit;
    }

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String entryId) {
        this.entryId = entryId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getDebit() {
        return debit;
    }

    public void setDebit(BigDecimal debit) {
        this.debit = debit;
    }

    public BigDecimal getCredit() {
        return credit;
    }

    public void setCredit(BigDecimal credit) {
        this.credit = credit;
    }
}
