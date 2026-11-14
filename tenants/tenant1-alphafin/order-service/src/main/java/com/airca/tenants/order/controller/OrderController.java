package com.airca.tenants.order.controller;

import com.airca.tenants.common.config.TenantAircaProperties;
import com.airca.tenants.order.dto.BulkOrderItem;
import com.airca.tenants.order.dto.BulkOrderRequest;
import com.airca.tenants.order.dto.CheckoutRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/orders", "/api/v1/orders"})
public class OrderController {

    private final TenantAircaProperties properties;

    public OrderController(TenantAircaProperties properties) {
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
     * Intentional defect: nested request contains discountCode and deliberately executes
     * unguarded toUpperCase() on it, producing a real NullPointerException when the code is null.
     */
    @PostMapping("/checkout")
    public ResponseEntity<Map<String, Object>> checkout(@RequestBody(required = false) CheckoutRequest request) {
        String discountCode = (request != null) ? request.resolveDiscountCode() : null;

        // Deliberately executes unguarded toUpperCase() on discountCode, producing a real NullPointerException if null
        String normalizedCode = discountCode.toUpperCase();

        String orderId = (request != null && request.getOrderId() != null)
                ? request.getOrderId()
                : UUID.randomUUID().toString();

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "orderId", orderId,
                "discountCode", normalizedCode,
                "service", properties.getServiceName()
        ));
    }

    /**
     * Intentional defect: batch of >5 items reliably raises real IndexOutOfBoundsException
     * due to an off-by-one <= items.size() loop; batches of 5 or fewer succeed.
     */
    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> bulk(@RequestBody(required = false) BulkOrderRequest request) {
        List<BulkOrderItem> items = (request != null && request.getItems() != null)
                ? request.getItems()
                : Collections.emptyList();

        List<Map<String, Object>> processed = new ArrayList<>();

        if (items.size() > 5) {
            // Bulk processing path: intentional off-by-one <= items.size() loop
            for (int i = 0; i <= items.size(); i++) {
                BulkOrderItem item = items.get(i);
                processed.add(Map.of(
                        "index", i,
                        "orderId", (item != null && item.getOrderId() != null) ? item.getOrderId() : ("batch-item-" + i)
                ));
            }
        } else {
            // Batches of 5 or fewer succeed normally
            for (int i = 0; i < items.size(); i++) {
                BulkOrderItem item = items.get(i);
                processed.add(Map.of(
                        "index", i,
                        "orderId", (item != null && item.getOrderId() != null) ? item.getOrderId() : ("batch-item-" + i)
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "processedCount", processed.size(),
                "items", processed,
                "service", properties.getServiceName()
        ));
    }
}
