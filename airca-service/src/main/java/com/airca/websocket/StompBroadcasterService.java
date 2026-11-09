package com.airca.websocket;

import com.airca.dto.IncidentDto;
import com.airca.dto.TelemetryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class StompBroadcasterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StompBroadcasterService.class);
    private final SimpMessagingTemplate messagingTemplate;

    public StompBroadcasterService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastIncident(IncidentDto incident) {
        send("/topic/tenant/" + incident.tenantId() + "/incidents", incident);
    }

    public void broadcastTelemetry(TelemetryDto telemetry) {
        send("/topic/tenant/" + telemetry.tenantId() + "/telemetry", telemetry);
    }

    private void send(String destination, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to publish WebSocket event to {}", destination, exception);
        }
    }
}
