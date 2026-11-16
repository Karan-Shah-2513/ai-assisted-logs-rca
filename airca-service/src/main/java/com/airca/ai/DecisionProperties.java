package com.airca.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.decision")
public class DecisionProperties {

    public static final String DEFAULT_MODEL = "gemini-decision-1";
    public static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com";
    public static final String DEFAULT_API_VERSION = "v1beta";
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private String model = DEFAULT_MODEL;
    private String endpoint = DEFAULT_ENDPOINT;
    private String apiVersion = DEFAULT_API_VERSION;
    private boolean enabled = true;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    @Value("${GEMINI_API_KEY:}")
    private String apiKey;

    public DecisionProperties() {
    }

    public DecisionProperties(
            String model,
            String endpoint,
            String apiVersion,
            boolean enabled,
            int timeoutSeconds) {
        this(model, endpoint, apiVersion, enabled, timeoutSeconds, null);
    }

    public DecisionProperties(
            String model,
            String endpoint,
            String apiVersion,
            boolean enabled,
            int timeoutSeconds,
            String apiKey) {
        this.model = model != null && !model.isBlank() ? model.trim() : DEFAULT_MODEL;
        this.endpoint = endpoint != null && !endpoint.isBlank() ? endpoint.trim() : DEFAULT_ENDPOINT;
        this.apiVersion = apiVersion != null && !apiVersion.isBlank() ? apiVersion.trim() : DEFAULT_API_VERSION;
        this.enabled = enabled;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.apiKey = apiKey != null ? apiKey.trim() : null;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
