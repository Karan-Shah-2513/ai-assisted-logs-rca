package com.airca.tenants.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DeviceMetadata {

    private String fingerprint;
    private String deviceId;
    private String userAgent;
    private String ipAddress;

    public DeviceMetadata() {
    }

    public DeviceMetadata(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public DeviceMetadata(String fingerprint, String deviceId, String userAgent, String ipAddress) {
        this.fingerprint = fingerprint;
        this.deviceId = deviceId;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
}
