package com.airca.tenants.wallet.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FxQuoteRequest {

    private String quoteId;
    private String currencyPair;
    private String pair;
    private String fromCurrency;
    private String toCurrency;
    private String baseCurrency;
    private String quoteCurrency;
    private BigDecimal amount;
    private BigDecimal rate;

    public FxQuoteRequest() {
    }

    public FxQuoteRequest(String currencyPair, BigDecimal amount) {
        this.currencyPair = currencyPair;
        this.amount = amount;
    }

    public String resolvePair() {
        if (currencyPair != null) {
            return currencyPair;
        }
        if (pair != null) {
            return pair;
        }
        if (fromCurrency != null && toCurrency != null) {
            return fromCurrency.trim() + "/" + toCurrency.trim();
        }
        if (baseCurrency != null && quoteCurrency != null) {
            return baseCurrency.trim() + "/" + quoteCurrency.trim();
        }
        return null;
    }

    public String getQuoteId() {
        return quoteId;
    }

    public void setQuoteId(String quoteId) {
        this.quoteId = quoteId;
    }

    public String getCurrencyPair() {
        return currencyPair;
    }

    public void setCurrencyPair(String currencyPair) {
        this.currencyPair = currencyPair;
    }

    public String getPair() {
        return pair;
    }

    public void setPair(String pair) {
        this.pair = pair;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public void setFromCurrency(String fromCurrency) {
        this.fromCurrency = fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }

    public void setToCurrency(String toCurrency) {
        this.toCurrency = toCurrency;
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public String getQuoteCurrency() {
        return quoteCurrency;
    }

    public void setQuoteCurrency(String quoteCurrency) {
        this.quoteCurrency = quoteCurrency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }
}
