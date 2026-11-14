package com.airca.websocket;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

class WebSocketConfigTest {

    @Test
    @DisplayName("WebSocketConfig installs StompTenantAuthChannelInterceptor on client inbound channel")
    void testConfigureClientInboundChannelInstallsInterceptor() {
        StompTenantAuthChannelInterceptor interceptor = mock(StompTenantAuthChannelInterceptor.class);
        WebSocketConfig config = new WebSocketConfig(interceptor);

        ChannelRegistration channelRegistration = mock(ChannelRegistration.class);
        config.configureClientInboundChannel(channelRegistration);

        verify(channelRegistration).interceptors(interceptor);
    }

    @Test
    @DisplayName("WebSocketConfig configures simple broker /topic and /app prefix")
    void testConfigureMessageBroker() {
        StompTenantAuthChannelInterceptor interceptor = mock(StompTenantAuthChannelInterceptor.class);
        WebSocketConfig config = new WebSocketConfig(interceptor);

        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    @DisplayName("WebSocketConfig registers /ws STOMP endpoint with SockJS")
    void testRegisterStompEndpoints() {
        StompTenantAuthChannelInterceptor interceptor = mock(StompTenantAuthChannelInterceptor.class);
        WebSocketConfig config = new WebSocketConfig(interceptor);

        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration registration = mock(StompWebSocketEndpointRegistration.class);
        when(registry.addEndpoint("/ws")).thenReturn(registration);
        when(registration.setAllowedOriginPatterns("*")).thenReturn(registration);

        config.registerStompEndpoints(registry);

        verify(registry).addEndpoint("/ws");
        verify(registration).setAllowedOriginPatterns("*");
        verify(registration).withSockJS();
    }
}
