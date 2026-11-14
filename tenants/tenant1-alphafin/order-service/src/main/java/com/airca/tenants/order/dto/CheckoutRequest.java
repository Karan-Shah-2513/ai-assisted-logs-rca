package com.airca.tenants.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CheckoutRequest {

    private String orderId;
    private String customerId;
    private Double amount;
    private List<String> items;
    private DiscountDetails discount;
    private DiscountDetails nested;
    private DiscountDetails details;
    private String discountCode;

    public CheckoutRequest() {
    }

    public CheckoutRequest(String orderId, DiscountDetails discount) {
        this.orderId = orderId;
        this.discount = discount;
    }

    public CheckoutRequest(String orderId, String discountCode) {
        this.orderId = orderId;
        this.discountCode = discountCode;
    }

    public String resolveDiscountCode() {
        if (discount != null) {
            return discount.getDiscountCode();
        }
        if (nested != null) {
            return nested.getDiscountCode();
        }
        if (details != null) {
            return details.getDiscountCode();
        }
        return discountCode;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    public List<String> getItems() {
        return items;
    }

    public void setItems(List<String> items) {
        this.items = items;
    }

    public DiscountDetails getDiscount() {
        return discount;
    }

    public void setDiscount(DiscountDetails discount) {
        this.discount = discount;
    }

    public DiscountDetails getNested() {
        return nested;
    }

    public void setNested(DiscountDetails nested) {
        this.nested = nested;
    }

    public DiscountDetails getDetails() {
        return details;
    }

    public void setDetails(DiscountDetails details) {
        this.details = details;
    }

    public String getDiscountCode() {
        return discountCode;
    }

    public void setDiscountCode(String discountCode) {
        this.discountCode = discountCode;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DiscountDetails {
        private String discountCode;
        private Double percentage;

        public DiscountDetails() {
        }

        public DiscountDetails(String discountCode) {
            this.discountCode = discountCode;
        }

        public DiscountDetails(String discountCode, Double percentage) {
            this.discountCode = discountCode;
            this.percentage = percentage;
        }

        public String getDiscountCode() {
            return discountCode;
        }

        public void setDiscountCode(String discountCode) {
            this.discountCode = discountCode;
        }

        public Double getPercentage() {
            return percentage;
        }

        public void setPercentage(Double percentage) {
            this.percentage = percentage;
        }
    }
}
