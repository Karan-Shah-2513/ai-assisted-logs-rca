package com.airca.tenants.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BulkOrderItem {

    private String orderId;
    private Double amount;
    private String item;

    public BulkOrderItem() {
    }

    public BulkOrderItem(String orderId) {
        this.orderId = orderId;
    }

    public BulkOrderItem(String orderId, Double amount, String item) {
        this.orderId = orderId;
        this.amount = amount;
        this.item = item;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public String getItem() {
        return item;
    }

    public void setItem(String item) {
        this.item = item;
    }
}
