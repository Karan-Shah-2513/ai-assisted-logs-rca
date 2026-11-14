package com.airca.tenants.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.airca.tenants.common.advice.TenantGlobalExceptionHandler;
import com.airca.tenants.common.reporter.AircaLogReporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "tenant.airca.enabled=true"
})
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AircaLogReporter aircaLogReporter;

    @Test
    @DisplayName("Health endpoint returns UP status and order-service identifier")
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/api/orders/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("order-service"));

        mockMvc.perform(get("/api/v1/orders/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("order-service"));
    }

    @Test
    @DisplayName("POST /api/orders/checkout with valid discount code succeeds (200)")
    void testCheckoutWithValidDiscount() throws Exception {
        String requestJson = """
                {
                    "orderId": "ORD-1001",
                    "customerId": "CUST-01",
                    "amount": 149.99,
                    "discount": {
                        "discountCode": "alphafin20",
                        "percentage": 20.0
                    }
                }
                """;

        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.orderId").value("ORD-1001"))
                .andExpect(jsonPath("$.discountCode").value("ALPHAFIN20"))
                .andExpect(jsonPath("$.service").value("order-service"));
    }

    @Test
    @DisplayName("POST /api/orders/checkout with null discountCode triggers real NPE and returns 500 via advice")
    void testCheckoutNullDiscountThrowsNpeAndReturns500() throws Exception {
        String requestJson = """
                {
                    "orderId": "ORD-NPE-01",
                    "customerId": "CUST-02",
                    "amount": 79.50,
                    "discount": {
                        "discountCode": null
                    }
                }
                """;

        mockMvc.perform(post("/api/orders/checkout")
                        .header("X-Correlation-ID", "corr-npe-checkout-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TenantGlobalExceptionHandler.CORRELATION_HEADER, "corr-npe-checkout-1"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.correlationId").value("corr-npe-checkout-1"));

        // Verify exception propagation into shared RestControllerAdvice reports full stack trace to Airca
        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("order-service"),
                stackTraceCaptor.capture(),
                eq("corr-npe-checkout-1")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("NullPointerException")
                .contains("toUpperCase")
                .contains("OrderController.checkout");
    }

    @Test
    @DisplayName("POST /api/orders/checkout with top-level null discountCode or empty body returns 500")
    void testCheckoutTopLevelNullDiscountAndEmptyBody() throws Exception {
        String nullTopLevelJson = "{\"orderId\":\"ORD-NULL-TOP\",\"discountCode\":null}";
        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullTopLevelJson))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));

        mockMvc.perform(post("/api/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    @DisplayName("POST /api/orders/bulk with exactly 5 items succeeds (boundary test: 5 -> success)")
    void testBulkWithFiveItemsSucceeds() throws Exception {
        String requestJson = """
                {
                    "items": [
                        {"orderId": "item-1", "amount": 10.0},
                        {"orderId": "item-2", "amount": 20.0},
                        {"orderId": "item-3", "amount": 30.0},
                        {"orderId": "item-4", "amount": 40.0},
                        {"orderId": "item-5", "amount": 50.0}
                    ]
                }
                """;

        mockMvc.perform(post("/api/orders/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.processedCount").value(5))
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    @DisplayName("POST /api/orders/bulk with exactly 6 items triggers real IndexOutOfBoundsException (boundary test: 6 -> 500)")
    void testBulkWithSixItemsFailsWith500() throws Exception {
        String requestJson = """
                {
                    "items": [
                        {"orderId": "item-1"},
                        {"orderId": "item-2"},
                        {"orderId": "item-3"},
                        {"orderId": "item-4"},
                        {"orderId": "item-5"},
                        {"orderId": "item-6"}
                    ]
                }
                """;

        mockMvc.perform(post("/api/orders/bulk")
                        .header("X-Correlation-ID", "corr-bulk-ioob-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(TenantGlobalExceptionHandler.CORRELATION_HEADER, "corr-bulk-ioob-6"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.correlationId").value("corr-bulk-ioob-6"));

        // Verify exception propagation into shared RestControllerAdvice reports full stack trace to Airca
        ArgumentCaptor<String> stackTraceCaptor = ArgumentCaptor.forClass(String.class);
        verify(aircaLogReporter, atLeastOnce()).reportError(
                eq("order-service"),
                stackTraceCaptor.capture(),
                eq("corr-bulk-ioob-6")
        );

        String stackTrace = stackTraceCaptor.getValue();
        assertThat(stackTrace)
                .contains("IndexOutOfBoundsException")
                .contains("OrderController.bulk");
    }

    @Test
    @DisplayName("POST /api/orders/bulk with small batch (0 or 1 item) succeeds")
    void testBulkSmallBatchSucceeds() throws Exception {
        mockMvc.perform(post("/api/orders/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": []}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(0));

        mockMvc.perform(post("/api/orders/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"orderId\": \"single\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(1));
    }

    @Test
    @DisplayName("POST /api/orders/bulk with raw JSON array format handles boundary 5 -> 200 and 6 -> 500")
    void testBulkWithRawJsonArray() throws Exception {
        String arrayFive = """
                [
                    {"orderId": "b-1"},
                    {"orderId": "b-2"},
                    {"orderId": "b-3"},
                    {"orderId": "b-4"},
                    {"orderId": "b-5"}
                ]
                """;
        mockMvc.perform(post("/api/orders/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arrayFive))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.processedCount").value(5));

        String arraySix = """
                [
                    {"orderId": "b-1"},
                    {"orderId": "b-2"},
                    {"orderId": "b-3"},
                    {"orderId": "b-4"},
                    {"orderId": "b-5"},
                    {"orderId": "b-6"}
                ]
                """;
        mockMvc.perform(post("/api/orders/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arraySix))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test
    @DisplayName("POST /api/v1/orders paths are also supported for both checkout and bulk")
    void testV1EndpointsSupport() throws Exception {
        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"v1-ok\",\"discount\":{\"discountCode\":\"v1promo\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountCode").value("V1PROMO"));

        mockMvc.perform(post("/api/v1/orders/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"v1-fail\",\"discount\":{\"discountCode\":null}}"))
                .andExpect(status().isInternalServerError());

        mockMvc.perform(post("/api/v1/orders/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"orderId\":\"1\"},{\"orderId\":\"2\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(2));
    }
}
