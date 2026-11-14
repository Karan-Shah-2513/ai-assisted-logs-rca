package com.airca.tenants.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "tenant.airca.enabled=false"
})
class OrderServiceApplicationTest {

    @Test
    void contextLoads() {
    }
}
