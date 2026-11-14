package com.airca.websocket;

/**
 * Exception thrown when STOMP WebSocket authentication or authorization fails.
 * Extends IllegalArgumentException so it integrates cleanly with Spring Messaging channels.
 */
public class StompAuthorizationException extends IllegalArgumentException {

    public StompAuthorizationException(String message) {
        super(message);
    }

    public StompAuthorizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
