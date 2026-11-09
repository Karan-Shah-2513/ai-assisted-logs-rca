package com.airca.tenants.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "tenant.airca.enabled=false"
})
class WalletServiceApplicationTest {

    @Test
    void contextLoads() {
    }
}
