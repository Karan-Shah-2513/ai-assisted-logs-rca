package com.airca.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class WebMvcCorsConfigTest {

    @Test
    @DisplayName("CORS configuration includes X-API-Key in allowed headers")
    void testCorsAllowedHeadersIncludesXApiKey() throws Exception {
        WebMvcCorsConfig config = new WebMvcCorsConfig(List.of("http://localhost:5173"));
        CorsRegistry registry = new CorsRegistry();
        config.addCorsMappings(registry);

        var getCorsConfigurationsMethod = CorsRegistry.class.getDeclaredMethod("getCorsConfigurations");
        getCorsConfigurationsMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, CorsConfiguration>) getCorsConfigurationsMethod.invoke(registry);

        CorsConfiguration corsConfig = map.get("/api/**");
        assertTrue(corsConfig.getAllowedHeaders().contains("X-API-Key"), "Allowed headers must contain X-API-Key");
        assertTrue(corsConfig.getAllowedHeaders().contains("X-Tenant-ID"), "Allowed headers must contain X-Tenant-ID");
    }
}
