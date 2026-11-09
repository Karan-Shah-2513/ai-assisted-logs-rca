package com.airca.tenants.order.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BulkOrderRequest {

    private List<BulkOrderItem> items;

    public BulkOrderRequest() {
        this.items = new ArrayList<>();
    }

    public BulkOrderRequest(List<BulkOrderItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    public List<BulkOrderItem> getItems() {
        return items;
    }

    public void setItems(List<BulkOrderItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    @JsonCreator
    public static BulkOrderRequest fromJson(JsonNode node) {
        BulkOrderRequest request = new BulkOrderRequest();
        List<BulkOrderItem> list = new ArrayList<>();

        if (node == null || node.isNull()) {
            request.setItems(list);
            return request;
        }

        if (node.isArray()) {
            for (JsonNode itemNode : node) {
                list.add(parseItem(itemNode));
            }
        } else if (node.isObject()) {
            JsonNode arrayNode = null;
            if (node.has("items")) {
                arrayNode = node.get("items");
            } else if (node.has("orders")) {
                arrayNode = node.get("orders");
            }

            if (arrayNode != null && arrayNode.isArray()) {
                for (JsonNode itemNode : arrayNode) {
                    list.add(parseItem(itemNode));
                }
            }
        }

        request.setItems(list);
        return request;
    }

    private static BulkOrderItem parseItem(JsonNode node) {
        if (node == null || node.isNull()) {
            return new BulkOrderItem();
        }
        if (node.isTextual()) {
            return new BulkOrderItem(node.asText());
        }
        String orderId = node.has("orderId") ? node.get("orderId").asText() : null;
        if (orderId == null && node.has("id")) {
            orderId = node.get("id").asText();
        }
        Double amount = (node.has("amount") && !node.get("amount").isNull())
                ? node.get("amount").asDouble()
                : null;
        String item = node.has("item") ? node.get("item").asText() : null;
        return new BulkOrderItem(orderId, amount, item);
    }
}
