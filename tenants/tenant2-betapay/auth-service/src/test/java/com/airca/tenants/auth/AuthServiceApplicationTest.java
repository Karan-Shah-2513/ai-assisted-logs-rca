package com.airca.tenants.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "tenant.airca.enabled=false"
})
class AuthServiceApplicationTest {

    @Test
    void contextLoads() {
    }
}
