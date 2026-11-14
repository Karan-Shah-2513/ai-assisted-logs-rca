package com.airca.websocket;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * Principal representing an authenticated tenant in STOMP WebSocket sessions.
 */
public record StompTenantPrincipal(UUID tenantId) implements Principal {

    public StompTenantPrincipal {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
    }

    @Override
    public String getName() {
        return tenantId.toString();
    }
}
