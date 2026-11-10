package com.airca.integration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.airca.multitenancy.TenantApiKeyService;
import com.airca.websocket.StompAuthorizationException;
import com.airca.websocket.StompTenantAuthChannelInterceptor;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

class StompTenantIsolationIntegrationTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TENANT_A_KEY = "integration-alpha-key";
    private static final String TENANT_B_KEY = "integration-beta-key";

    private ExecutorSubscribableChannel inboundChannel;

    @BeforeEach
    void configureInboundChannel() {
        TenantApiKeyService apiKeyService = new TenantApiKeyService(Map.of(
                TENANT_A, Set.of(TENANT_A_KEY),
                TENANT_B, Set.of(TENANT_B_KEY)));
        inboundChannel = new ExecutorSubscribableChannel(new SyncTaskExecutor());
        inboundChannel.addInterceptor(new StompTenantAuthChannelInterceptor(apiKeyService));
    }

    @Test
    void authenticatedSessionsCanSubscribeOnlyToTheirOwnTenantTopics() {
        assertTrue(inboundChannel.send(connect("session-a", TENANT_A_KEY)));
        assertTrue(inboundChannel.send(connect("session-b", TENANT_B_KEY)));
        assertTrue(inboundChannel.send(subscribe("session-a", "/topic/tenant/" + TENANT_A + "/incidents")));
        assertTrue(inboundChannel.send(subscribe("session-b", "/topic/tenant/" + TENANT_B + "/telemetry")));

        assertRejected(() -> inboundChannel.send(subscribe("session-a", "/topic/tenant/" + TENANT_B + "/incidents")));
        assertRejected(() -> inboundChannel.send(subscribe("session-b", "/topic/tenant/" + TENANT_A + "/telemetry")));
    }

    @Test
    void rejectsBroadSubscriptionsAndSubscriptionsAfterDisconnect() {
        assertTrue(inboundChannel.send(connect("session-a", TENANT_A_KEY)));
        assertRejected(() -> inboundChannel.send(subscribe("session-a", "/topic/tenant/" + TENANT_A + "/**")));

        StompHeaderAccessor disconnect = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disconnect.setSessionId("session-a");
        assertTrue(inboundChannel.send(message(disconnect)));

        assertRejected(() -> inboundChannel.send(subscribe("session-a", "/topic/tenant/" + TENANT_A + "/incidents")));
    }

    private static void assertRejected(Runnable send) {
        MessageDeliveryException exception = assertThrows(MessageDeliveryException.class, send::run);
        assertTrue(exception.getCause() instanceof StompAuthorizationException);
    }

    private static Message<?> connect(String sessionId, String apiKey) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setSessionAttributes(new ConcurrentHashMap<>());
        accessor.setNativeHeader("X-API-Key", apiKey);
        return message(accessor);
    }

    private static Message<?> subscribe(String sessionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        return message(accessor);
    }

    private static Message<?> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
