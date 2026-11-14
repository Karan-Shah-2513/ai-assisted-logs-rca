package com.airca.tenants.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "tenant.airca.enabled=false"
})
class LedgerServiceApplicationTest {

    @Test
    void contextLoads() {
    }
}
