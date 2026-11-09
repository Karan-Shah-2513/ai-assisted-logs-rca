package com.airca.websocket;

import com.airca.multitenancy.TenantApiKeyService;
import com.airca.multitenancy.TenantFilter;
import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * ChannelInterceptor installed on the client inbound channel that enforces tenant
 * authorization for STOMP WebSocket frames (CONNECT, SUBSCRIBE, SEND).
 */
@Component
public class StompTenantAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(StompTenantAuthChannelInterceptor.class);

    public static final String SESSION_TENANT_ID_ATTR = "TENANT_ID";

    private static final Pattern TENANT_TOPIC_PATTERN =
            Pattern.compile("^/topic/tenant/([^/]+)/(incidents|telemetry)$");

    private final TenantApiKeyService apiKeyService;
    private final Map<String, UUID> sessionTenantMap = new ConcurrentHashMap<>();

    public StompTenantAuthChannelInterceptor(TenantApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }

        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        return switch (command) {
            case CONNECT, STOMP -> handleConnect(message, accessor);
            case SUBSCRIBE -> {
                handleSubscribe(accessor);
                yield message;
            }
            case SEND -> {
                handleSend(accessor);
                yield message;
            }
            case DISCONNECT -> {
                handleDisconnect(accessor);
                yield message;
            }
            default -> message;
        };
    }

    private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
        // 1. Validate X-API-Key (never log key values)
        String apiKey = getHeader(accessor, TenantFilter.API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.warn("STOMP CONNECT rejected: missing {} header", TenantFilter.API_KEY_HEADER);
            throw new StompAuthorizationException("Missing or invalid API key");
        }

        Optional<UUID> tenantIdOpt = apiKeyService.resolveTenantId(apiKey);
        if (tenantIdOpt.isEmpty()) {
            LOGGER.warn("STOMP CONNECT rejected: invalid API key");
            throw new StompAuthorizationException("Missing or invalid API key");
        }
        UUID authenticatedTenantId = tenantIdOpt.get();

        // 2. Validate optional X-Tenant-ID header (must be canonical UUID and match authenticated key)
        String tenantHeader = getHeader(accessor, TenantFilter.TENANT_HEADER);
        if (tenantHeader != null && !tenantHeader.isBlank()) {
            UUID requestedTenantId;
            try {
                requestedTenantId = UUID.fromString(tenantHeader.trim());
                if (!requestedTenantId.toString().equalsIgnoreCase(tenantHeader.trim())) {
                    LOGGER.warn("STOMP CONNECT rejected: non-canonical X-Tenant-ID header");
                    throw new StompAuthorizationException("X-Tenant-ID must be a valid canonical UUID");
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warn("STOMP CONNECT rejected: malformed X-Tenant-ID header");
                throw new StompAuthorizationException("X-Tenant-ID must be a valid UUID");
            }

            if (!requestedTenantId.equals(authenticatedTenantId)) {
                LOGGER.warn("STOMP CONNECT rejected: X-Tenant-ID does not match authenticated tenant");
                throw new StompAuthorizationException("X-Tenant-ID does not match authenticated tenant");
            }
        }

        // 3. Store authenticated tenant identity in STOMP session state / Principal (no thread-local state)
        StompHeaderAccessor mutableAccessor = getMutableAccessor(message, accessor);
        mutableAccessor.setUser(new StompTenantPrincipal(authenticatedTenantId));

        Map<String, Object> sessionAttributes = mutableAccessor.getSessionAttributes();
        if (sessionAttributes == null) {
            sessionAttributes = new ConcurrentHashMap<>();
            mutableAccessor.setSessionAttributes(sessionAttributes);
        }
        sessionAttributes.put(SESSION_TENANT_ID_ATTR, authenticatedTenantId);

        String sessionId = mutableAccessor.getSessionId();
        if (sessionId != null) {
            sessionTenantMap.put(sessionId, authenticatedTenantId);
        }

        LOGGER.info("STOMP CONNECT authenticated successfully for tenant {}", authenticatedTenantId);
        return MessageBuilder.createMessage(message.getPayload(), mutableAccessor.getMessageHeaders());
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        // 1. Require previously authenticated session
        UUID sessionTenantId = resolveSessionTenant(accessor);
        if (sessionTenantId == null) {
            LOGGER.warn("STOMP SUBSCRIBE rejected: unauthenticated session");
            throw new StompAuthorizationException("Subscription rejected: session is not authenticated");
        }

        // 2. Validate destination
        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            LOGGER.warn("STOMP SUBSCRIBE rejected: destination is null or blank for tenant {}", sessionTenantId);
            throw new StompAuthorizationException("Subscription destination cannot be null or empty");
        }

        Matcher matcher = TENANT_TOPIC_PATTERN.matcher(destination);
        if (matcher.matches()) {
            String destTenantStr = matcher.group(1);
            UUID destTenantId;
            try {
                destTenantId = UUID.fromString(destTenantStr.trim());
                if (!destTenantId.toString().equalsIgnoreCase(destTenantStr.trim())) {
                    LOGGER.warn("STOMP SUBSCRIBE rejected: non-canonical tenant ID in destination {}", destination);
                    throw new StompAuthorizationException("Malformed tenant ID in subscription destination: " + destination);
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warn("STOMP SUBSCRIBE rejected: malformed tenant ID in destination {}", destination);
                throw new StompAuthorizationException("Malformed tenant ID in subscription destination: " + destination);
            }

            if (!destTenantId.equals(sessionTenantId)) {
                LOGGER.warn("STOMP SUBSCRIBE rejected: cross-tenant subscription attempt by tenant {} to destination {}",
                        sessionTenantId, destination);
                throw new StompAuthorizationException("Cross-tenant subscription forbidden for destination: " + destination);
            }

            // Destination matches exact allowed pattern for session's own tenant (/topic/tenant/{uuid}/incidents or telemetry)
            LOGGER.debug("STOMP SUBSCRIBE allowed for tenant {} to destination {}", sessionTenantId, destination);
            return;
        }

        // Check for broad / unscoped subscriptions or invalid destinations
        if (destination.contains("*") || destination.contains("#")
                || destination.equals("/topic") || destination.startsWith("/topic/")) {
            LOGGER.warn("STOMP SUBSCRIBE rejected: broad or unscoped subscription destination {} for tenant {}",
                    destination, sessionTenantId);
            throw new StompAuthorizationException("Broad or unscoped subscriptions are forbidden: " + destination);
        }

        LOGGER.warn("STOMP SUBSCRIBE rejected: unauthorized destination {} for tenant {}",
                destination, sessionTenantId);
        throw new StompAuthorizationException("Invalid or unauthorized subscription destination: " + destination);
    }

    private void handleSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        LOGGER.warn("STOMP SEND rejected for destination {}: client SEND is not permitted", destination);
        throw new StompAuthorizationException("Client SEND is not permitted");
    }

    private void handleDisconnect(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        if (sessionId != null) {
            sessionTenantMap.remove(sessionId);
        }
    }

    private UUID resolveSessionTenant(StompHeaderAccessor accessor) {
        // 1. Check Principal
        Principal user = accessor.getUser();
        if (user instanceof StompTenantPrincipal tenantPrincipal) {
            return tenantPrincipal.tenantId();
        }
        if (user != null && user.getName() != null) {
            try {
                return UUID.fromString(user.getName().trim());
            } catch (IllegalArgumentException ignored) {
            }
        }

        // 2. Check STOMP session attributes
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null) {
            Object attr = sessionAttributes.get(SESSION_TENANT_ID_ATTR);
            if (attr instanceof UUID uuid) {
                return uuid;
            }
            if (attr instanceof String s) {
                try {
                    return UUID.fromString(s.trim());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // 3. Check session ID map
        String sessionId = accessor.getSessionId();
        if (sessionId != null) {
            return sessionTenantMap.get(sessionId);
        }

        return null;
    }

    private String getHeader(StompHeaderAccessor accessor, String headerName) {
        String value = accessor.getFirstNativeHeader(headerName);
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        value = accessor.getFirstNativeHeader(headerName.toLowerCase());
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        Object headerVal = accessor.getHeader(headerName);
        if (headerVal instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        headerVal = accessor.getHeader(headerName.toLowerCase());
        if (headerVal instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    private StompHeaderAccessor getMutableAccessor(Message<?> message, StompHeaderAccessor accessor) {
        if (accessor != null && accessor.isMutable()) {
            return accessor;
        }
        MessageHeaderAccessor mutable = MessageHeaderAccessor.getMutableAccessor(message);
        if (mutable instanceof StompHeaderAccessor sha) {
            return sha;
        }
        return StompHeaderAccessor.wrap(message);
    }
}
