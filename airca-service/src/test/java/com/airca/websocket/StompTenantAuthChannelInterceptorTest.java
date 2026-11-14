package com.airca.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.airca.dto.IncidentDto;
import com.airca.dto.TelemetryDto;
import com.airca.model.IncidentStatus;
import com.airca.multitenancy.TenantApiKeyService;
import com.airca.multitenancy.TenantContext;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

class StompTenantAuthChannelInterceptorTest {

    private final UUID alphaFinId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID betaPayId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final String alphaFinKey = "alphafin-stomp-secret-1111";
    private final String betaPayKey = "betapay-stomp-secret-2222";

    private TenantApiKeyService apiKeyService;
    private StompTenantAuthChannelInterceptor interceptor;
    private MessageChannel dummyChannel;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        apiKeyService = new TenantApiKeyService(Map.of(
                alphaFinId, Set.of(alphaFinKey),
                betaPayId, Set.of(betaPayKey)
        ));
        interceptor = new StompTenantAuthChannelInterceptor(apiKeyService);
        dummyChannel = mock(MessageChannel.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Message<?> createConnectMessage(String sessionId, String apiKey, String tenantHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setSessionAttributes(new ConcurrentHashMap<>());
        if (apiKey != null) {
            accessor.setNativeHeader("X-API-Key", apiKey);
        }
        if (tenantHeader != null) {
            accessor.setNativeHeader("X-Tenant-ID", tenantHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> createSubscribeMessage(String sessionId, String destination, Principal user, Map<String, Object> sessionAttrs) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionId(sessionId);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        if (sessionAttrs != null) {
            accessor.setSessionAttributes(sessionAttrs);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> createSendMessage(String sessionId, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> createDisconnectMessage(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("Valid CONNECT with API key resolves tenant, sets Principal and session attributes, and avoids ThreadLocal")
    void testValidConnectSuccess() {
        Message<?> connectMsg = createConnectMessage("sess-1", alphaFinKey, null);
        Message<?> result = interceptor.preSend(connectMsg, dummyChannel);

        assertNotNull(result);
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertNotNull(accessor);

        Principal user = accessor.getUser();
        assertNotNull(user);
        assertEquals(alphaFinId.toString(), user.getName());
        if (user instanceof StompTenantPrincipal stp) {
            assertEquals(alphaFinId, stp.tenantId());
        }

        assertNotNull(accessor.getSessionAttributes());
        assertEquals(alphaFinId, accessor.getSessionAttributes().get(StompTenantAuthChannelInterceptor.SESSION_TENANT_ID_ATTR));

        // Must NOT store in ThreadLocal state
        assertFalse(TenantContext.getTenantId().isPresent(), "TenantContext must not be populated in STOMP interceptor");
    }

    @Test
    @DisplayName("Valid CONNECT with matching X-Tenant-ID header succeeds")
    void testValidConnectWithMatchingTenantHeader() {
        Message<?> connectMsg = createConnectMessage("sess-1", alphaFinKey, alphaFinId.toString());
        Message<?> result = interceptor.preSend(connectMsg, dummyChannel);
        assertNotNull(result);

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertNotNull(accessor);
        assertEquals(alphaFinId.toString(), accessor.getUser().getName());
    }

    @Test
    @DisplayName("Valid CONNECT allows SUBSCRIBE to incidents and telemetry topics for own tenant")
    void testValidSubscribeToIncidentsAndTelemetry() {
        String sessionId = "sess-alpha";
        Message<?> connectMsg = createConnectMessage(sessionId, alphaFinKey, null);
        Message<?> connResult = interceptor.preSend(connectMsg, dummyChannel);
        StompHeaderAccessor connAccessor = MessageHeaderAccessor.getAccessor(connResult, StompHeaderAccessor.class);

        // 1. Subscribe to incidents
        Message<?> subIncidents = createSubscribeMessage(
                sessionId,
                "/topic/tenant/" + alphaFinId + "/incidents",
                connAccessor.getUser(),
                connAccessor.getSessionAttributes()
        );
        Message<?> subIncidentsResult = interceptor.preSend(subIncidents, dummyChannel);
        assertNotNull(subIncidentsResult);

        // 2. Subscribe to telemetry
        Message<?> subTelemetry = createSubscribeMessage(
                sessionId,
                "/topic/tenant/" + alphaFinId + "/telemetry",
                connAccessor.getUser(),
                connAccessor.getSessionAttributes()
        );
        Message<?> subTelemetryResult = interceptor.preSend(subTelemetry, dummyChannel);
        assertNotNull(subTelemetryResult);

        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("CONNECT missing X-API-Key header is rejected")
    void testConnectMissingApiKeyThrows() {
        Message<?> connectMsg = createConnectMessage("sess-missing", null, null);

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(connectMsg, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("Missing or invalid API key"));
        assertFalse(TenantContext.getTenantId().isPresent());
    }

    @Test
    @DisplayName("CONNECT with empty or blank X-API-Key is rejected")
    void testConnectBlankApiKeyThrows() {
        Message<?> connectMsg = createConnectMessage("sess-blank", "   ", null);

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(connectMsg, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("Missing or invalid API key"));
    }

    @Test
    @DisplayName("CONNECT with invalid X-API-Key is rejected and does not log secrets")
    void testConnectInvalidApiKeyThrows() {
        Message<?> connectMsg = createConnectMessage("sess-invalid", "unknown-secret-token-xyz", null);

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(connectMsg, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("Missing or invalid API key"));
        // Check secret is not in exception message
        assertFalse(ex.getMessage().contains("unknown-secret-token-xyz"));
    }

    @Test
    @DisplayName("CONNECT with spoofed X-Tenant-ID differing from authenticated key is rejected")
    void testConnectSpoofedTenantHeaderThrows() {
        // Authenticating with AlphaFin key but requesting BetaPay tenant ID
        Message<?> connectMsg = createConnectMessage("sess-spoof", alphaFinKey, betaPayId.toString());

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(connectMsg, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("X-Tenant-ID does not match authenticated tenant"));
    }

    @Test
    @DisplayName("CONNECT with malformed X-Tenant-ID is rejected")
    void testConnectMalformedTenantHeaderThrows() {
        Message<?> connectMsg = createConnectMessage("sess-malformed", alphaFinKey, "not-a-uuid");

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(connectMsg, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("X-Tenant-ID must be a valid UUID"));
    }

    @Test
    @DisplayName("CONNECT with non-canonical X-Tenant-ID is rejected")
    void testConnectNonCanonicalTenantHeaderThrows() {
        Message<?> connectMsg = createConnectMessage("sess-non-canonical", alphaFinKey, alphaFinId + "extra");

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(connectMsg, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("X-Tenant-ID must be a valid"));
    }

    @Test
    @DisplayName("SUBSCRIBE to cross-tenant incidents topic is rejected")
    void testCrossTenantIncidentsSubscribeThrows() {
        String sessionId = "sess-alpha";
        Message<?> connectMsg = createConnectMessage(sessionId, alphaFinKey, null);
        Message<?> connResult = interceptor.preSend(connectMsg, dummyChannel);
        StompHeaderAccessor connAccessor = MessageHeaderAccessor.getAccessor(connResult, StompHeaderAccessor.class);

        // AlphaFin tries to subscribe to BetaPay's incidents
        Message<?> crossSub = createSubscribeMessage(
                sessionId,
                "/topic/tenant/" + betaPayId + "/incidents",
                connAccessor.getUser(),
                connAccessor.getSessionAttributes()
        );

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(crossSub, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("Cross-tenant subscription forbidden"));
    }

    @Test
    @DisplayName("SUBSCRIBE to cross-tenant telemetry topic is rejected")
    void testCrossTenantTelemetrySubscribeThrows() {
        String sessionId = "sess-alpha";
        Message<?> connectMsg = createConnectMessage(sessionId, alphaFinKey, null);
        Message<?> connResult = interceptor.preSend(connectMsg, dummyChannel);
        StompHeaderAccessor connAccessor = MessageHeaderAccessor.getAccessor(connResult, StompHeaderAccessor.class);

        // AlphaFin tries to subscribe to BetaPay's telemetry
        Message<?> crossSub = createSubscribeMessage(
                sessionId,
                "/topic/tenant/" + betaPayId + "/telemetry",
                connAccessor.getUser(),
                connAccessor.getSessionAttributes()
        );

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(crossSub, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("Cross-tenant subscription forbidden"));
    }

    @Test
    @DisplayName("SUBSCRIBE without prior authentication is rejected")
    void testUnauthenticatedSubscribeThrows() {
        Message<?> subMsg = createSubscribeMessage(
                "unauth-sess",
                "/topic/tenant/" + alphaFinId + "/incidents",
                null,
                null
        );

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(subMsg, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("session is not authenticated"));
    }

    @Test
    @DisplayName("SUBSCRIBE to broad or wildcard destinations is rejected")
    void testBroadOrWildcardSubscribeThrows() {
        String sessionId = "sess-alpha";
        Message<?> connectMsg = createConnectMessage(sessionId, alphaFinKey, null);
        Message<?> connResult = interceptor.preSend(connectMsg, dummyChannel);
        StompHeaderAccessor connAccessor = MessageHeaderAccessor.getAccessor(connResult, StompHeaderAccessor.class);

        String[] broadDestinations = {
                "/topic/**",
                "/topic/tenant/**",
                "/topic/tenant/" + alphaFinId + "/*",
                "/topic/incidents",
                "/topic",
                "/topic/"
        };

        for (String broadDest : broadDestinations) {
            Message<?> subMsg = createSubscribeMessage(
                    sessionId,
                    broadDest,
                    connAccessor.getUser(),
                    connAccessor.getSessionAttributes()
            );

            StompAuthorizationException ex = assertThrows(
                    StompAuthorizationException.class,
                    () -> interceptor.preSend(subMsg, dummyChannel),
                    "Expected broad subscription to fail for " + broadDest
            );
            assertTrue(ex.getMessage().contains("Broad or unscoped subscriptions are forbidden")
                    || ex.getMessage().contains("Invalid or unauthorized subscription destination"));
        }
    }

    @Test
    @DisplayName("SUBSCRIBE with malformed destination or unknown subpath is rejected")
    void testMalformedOrUnknownDestinationThrows() {
        String sessionId = "sess-alpha";
        Message<?> connectMsg = createConnectMessage(sessionId, alphaFinKey, null);
        Message<?> connResult = interceptor.preSend(connectMsg, dummyChannel);
        StompHeaderAccessor connAccessor = MessageHeaderAccessor.getAccessor(connResult, StompHeaderAccessor.class);

        // Malformed non-UUID
        Message<?> malformedUuidSub = createSubscribeMessage(
                sessionId,
                "/topic/tenant/not-a-valid-uuid/incidents",
                connAccessor.getUser(),
                connAccessor.getSessionAttributes()
        );
        StompAuthorizationException ex1 = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(malformedUuidSub, dummyChannel)
        );
        assertTrue(ex1.getMessage().contains("Malformed tenant ID"));

        // Null destination
        Message<?> nullDestSub = createSubscribeMessage(
                sessionId,
                null,
                connAccessor.getUser(),
                connAccessor.getSessionAttributes()
        );
        assertThrows(StompAuthorizationException.class, () -> interceptor.preSend(nullDestSub, dummyChannel));

        // Blank destination
        Message<?> blankDestSub = createSubscribeMessage(
                sessionId,
                "   ",
                connAccessor.getUser(),
                connAccessor.getSessionAttributes()
        );
        assertThrows(StompAuthorizationException.class, () -> interceptor.preSend(blankDestSub, dummyChannel));

        // Unsupported subtopic
        Message<?> otherSub = createSubscribeMessage(
                sessionId,
                "/topic/tenant/" + alphaFinId + "/admin-logs",
                connAccessor.getUser(),
                connAccessor.getSessionAttributes()
        );
        assertThrows(StompAuthorizationException.class, () -> interceptor.preSend(otherSub, dummyChannel));
    }

    @Test
    @DisplayName("Client SEND command directly into broker topics is rejected")
    void testClientSendRejected() {
        Message<?> sendMsg = createSendMessage("sess-alpha", "/topic/tenant/" + alphaFinId + "/incidents");

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(sendMsg, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("Client SEND is not permitted"));
    }

    @Test
    @DisplayName("Client SEND command to application destination prefix is also rejected")
    void testClientSendToAppRejected() {
        Message<?> sendMsg = createSendMessage("sess-alpha", "/app/chat");

        StompAuthorizationException ex = assertThrows(
                StompAuthorizationException.class,
                () -> interceptor.preSend(sendMsg, dummyChannel)
        );
        assertTrue(ex.getMessage().contains("Client SEND is not permitted"));
    }

    @Test
    @DisplayName("Complete session isolation across multiple concurrent session IDs")
    void testSessionIsolationAcrossSessionIds() {
        String sessionAlpha = "sess-alpha-001";
        String sessionBeta = "sess-beta-002";

        // Connect AlphaFin
        Message<?> connAlpha = createConnectMessage(sessionAlpha, alphaFinKey, null);
        Message<?> connAlphaRes = interceptor.preSend(connAlpha, dummyChannel);
        StompHeaderAccessor accAlpha = MessageHeaderAccessor.getAccessor(connAlphaRes, StompHeaderAccessor.class);

        // Connect BetaPay
        Message<?> connBeta = createConnectMessage(sessionBeta, betaPayKey, null);
        Message<?> connBetaRes = interceptor.preSend(connBeta, dummyChannel);
        StompHeaderAccessor accBeta = MessageHeaderAccessor.getAccessor(connBetaRes, StompHeaderAccessor.class);

        // AlphaFin can subscribe to AlphaFin topics
        Message<?> subAlpha = createSubscribeMessage(
                sessionAlpha,
                "/topic/tenant/" + alphaFinId + "/incidents",
                accAlpha.getUser(),
                accAlpha.getSessionAttributes()
        );
        assertNotNull(interceptor.preSend(subAlpha, dummyChannel));

        // BetaPay can subscribe to BetaPay topics
        Message<?> subBeta = createSubscribeMessage(
                sessionBeta,
                "/topic/tenant/" + betaPayId + "/telemetry",
                accBeta.getUser(),
                accBeta.getSessionAttributes()
        );
        assertNotNull(interceptor.preSend(subBeta, dummyChannel));

        // AlphaFin cannot subscribe to BetaPay topics
        Message<?> subAlphaToBeta = createSubscribeMessage(
                sessionAlpha,
                "/topic/tenant/" + betaPayId + "/incidents",
                accAlpha.getUser(),
                accAlpha.getSessionAttributes()
        );
        assertThrows(StompAuthorizationException.class, () -> interceptor.preSend(subAlphaToBeta, dummyChannel));

        // BetaPay cannot subscribe to AlphaFin topics
        Message<?> subBetaToAlpha = createSubscribeMessage(
                sessionBeta,
                "/topic/tenant/" + alphaFinId + "/telemetry",
                accBeta.getUser(),
                accBeta.getSessionAttributes()
        );
        assertThrows(StompAuthorizationException.class, () -> interceptor.preSend(subBetaToAlpha, dummyChannel));

        // Disconnect AlphaFin
        Message<?> disconnAlpha = createDisconnectMessage(sessionAlpha);
        interceptor.preSend(disconnAlpha, dummyChannel);

        // BetaPay remains valid and isolated
        Message<?> subBeta2 = createSubscribeMessage(
                sessionBeta,
                "/topic/tenant/" + betaPayId + "/incidents",
                accBeta.getUser(),
                accBeta.getSessionAttributes()
        );
        assertNotNull(interceptor.preSend(subBeta2, dummyChannel));

        // AlphaFin session cannot subscribe after disconnect when using sessionId lookup
        Message<?> subAlphaAfterDisconnect = createSubscribeMessage(
                sessionAlpha,
                "/topic/tenant/" + alphaFinId + "/incidents",
                null,
                null
        );
        assertThrows(StompAuthorizationException.class, () -> interceptor.preSend(subAlphaAfterDisconnect, dummyChannel));
    }

    @Test
    @DisplayName("Non-STOMP or lifecycle messages without STOMP commands pass through cleanly")
    void testNonStompMessagesPassThrough() {
        Message<String> generic = new GenericMessage<>("plain text message");
        Message<?> result = interceptor.preSend(generic, dummyChannel);
        assertEquals(generic, result);
    }

    @Test
    @DisplayName("Preserve server-side broadcasts via StompBroadcasterService")
    void testServerSideBroadcastsPreserved() {
        SimpMessagingTemplate templateMock = mock(SimpMessagingTemplate.class);
        StompBroadcasterService broadcasterService = new StompBroadcasterService(templateMock);

        IncidentDto incident = new IncidentDto(
                UUID.randomUUID(),
                alphaFinId,
                "fingerprint-123",
                "PaymentGateway",
                "NullPointerException at line 42",
                "NullPointerException at line 42",
                1L,
                IncidentStatus.OPEN,
                null,
                Instant.now(),
                Instant.now()
        );

        TelemetryDto telemetry = new TelemetryDto(
                alphaFinId,
                1000L,
                400L,
                10L,
                0.40,
                150.0
        );

        broadcasterService.broadcastIncident(incident);
        verify(templateMock).convertAndSend("/topic/tenant/" + alphaFinId + "/incidents", incident);

        broadcasterService.broadcastTelemetry(telemetry);
        verify(templateMock).convertAndSend("/topic/tenant/" + alphaFinId + "/telemetry", telemetry);
    }
}
