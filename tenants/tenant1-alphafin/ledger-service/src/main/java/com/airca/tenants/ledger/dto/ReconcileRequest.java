package com.airca.tenants.ledger.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ReconcileRequest {

    private String reconciliationId;
    private BigDecimal totalDebit;
    private BigDecimal totalCredit;
    private List<ReconcileEntry> entries = new ArrayList<>();

    public ReconcileRequest() {
    }

    public ReconcileRequest(BigDecimal totalDebit, BigDecimal totalCredit) {
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
    }

    public ReconcileRequest(List<ReconcileEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
    }

    @JsonCreator
    public static ReconcileRequest fromJson(JsonNode node) {
        ReconcileRequest request = new ReconcileRequest();
        if (node == null || node.isNull()) {
            return request;
        }

        if (node.isArray()) {
            List<ReconcileEntry> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(parseEntry(item));
            }
            request.setEntries(list);
            return request;
        }

        if (node.isObject()) {
            if (node.has("reconciliationId")) {
                request.setReconciliationId(node.get("reconciliationId").asText());
            } else if (node.has("id")) {
                request.setReconciliationId(node.get("id").asText());
            }

            if (node.has("totalDebit") && !node.get("totalDebit").isNull()) {
                request.setTotalDebit(new BigDecimal(node.get("totalDebit").asText()));
            } else if (node.has("debitTotal") && !node.get("debitTotal").isNull()) {
                request.setTotalDebit(new BigDecimal(node.get("debitTotal").asText()));
            } else if (node.has("totalDebits") && !node.get("totalDebits").isNull()) {
                request.setTotalDebit(new BigDecimal(node.get("totalDebits").asText()));
            }

            if (node.has("totalCredit") && !node.get("totalCredit").isNull()) {
                request.setTotalCredit(new BigDecimal(node.get("totalCredit").asText()));
            } else if (node.has("creditTotal") && !node.get("creditTotal").isNull()) {
                request.setTotalCredit(new BigDecimal(node.get("creditTotal").asText()));
            } else if (node.has("totalCredits") && !node.get("totalCredits").isNull()) {
                request.setTotalCredit(new BigDecimal(node.get("totalCredits").asText()));
            }

            JsonNode entriesNode = null;
            if (node.has("entries")) {
                entriesNode = node.get("entries");
            } else if (node.has("items")) {
                entriesNode = node.get("items");
            } else if (node.has("records")) {
                entriesNode = node.get("records");
            }

            if (entriesNode != null && entriesNode.isArray()) {
                List<ReconcileEntry> list = new ArrayList<>();
                for (JsonNode item : entriesNode) {
                    list.add(parseEntry(item));
                }
                request.setEntries(list);
            }
        }

        return request;
    }

    private static ReconcileEntry parseEntry(JsonNode node) {
        if (node == null || node.isNull()) {
            return new ReconcileEntry();
        }
        ReconcileEntry entry = new ReconcileEntry();
        if (node.has("entryId")) {
            entry.setEntryId(node.get("entryId").asText());
        }
        if (node.has("accountId")) {
            entry.setAccountId(node.get("accountId").asText());
        }
        if (node.has("type")) {
            entry.setType(node.get("type").asText());
        }
        if (node.has("amount") && !node.get("amount").isNull()) {
            entry.setAmount(new BigDecimal(node.get("amount").asText()));
        }
        if (node.has("debit") && !node.get("debit").isNull()) {
            entry.setDebit(new BigDecimal(node.get("debit").asText()));
        }
        if (node.has("credit") && !node.get("credit").isNull()) {
            entry.setCredit(new BigDecimal(node.get("credit").asText()));
        }
        return entry;
    }

    public BigDecimal resolveTotalDebits() {
        if (entries != null && !entries.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (ReconcileEntry entry : entries) {
                if (entry.getDebit() != null) {
                    sum = sum.add(entry.getDebit());
                } else if ("DEBIT".equalsIgnoreCase(entry.getType()) && entry.getAmount() != null) {
                    sum = sum.add(entry.getAmount());
                }
            }
            return sum;
        }
        return totalDebit != null ? totalDebit : BigDecimal.ZERO;
    }

    public BigDecimal resolveTotalCredits() {
        if (entries != null && !entries.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (ReconcileEntry entry : entries) {
                if (entry.getCredit() != null) {
                    sum = sum.add(entry.getCredit());
                } else if ("CREDIT".equalsIgnoreCase(entry.getType()) && entry.getAmount() != null) {
                    sum = sum.add(entry.getAmount());
                }
            }
            return sum;
        }
        return totalCredit != null ? totalCredit : BigDecimal.ZERO;
    }

    public String getReconciliationId() {
        return reconciliationId;
    }

    public void setReconciliationId(String reconciliationId) {
        this.reconciliationId = reconciliationId;
    }

    public BigDecimal getTotalDebit() {
        return totalDebit;
    }

    public void setTotalDebit(BigDecimal totalDebit) {
        this.totalDebit = totalDebit;
    }

    public BigDecimal getTotalCredit() {
        return totalCredit;
    }

    public void setTotalCredit(BigDecimal totalCredit) {
        this.totalCredit = totalCredit;
    }

    public List<ReconcileEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<ReconcileEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
    }
}
