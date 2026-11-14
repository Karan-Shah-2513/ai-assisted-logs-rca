package com.airca.tenants.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MfaRequest {

    private String userId;
    private String mfaCode;
    private String code;
    private DeviceMetadata deviceMetadata;
    private DeviceMetadata metadata;
    private String fingerprint;

    public MfaRequest() {
    }

    public MfaRequest(String userId, String mfaCode, DeviceMetadata deviceMetadata) {
        this.userId = userId;
        this.mfaCode = mfaCode;
        this.deviceMetadata = deviceMetadata;
    }

    @JsonCreator
    public static MfaRequest fromJson(JsonNode node) {
        MfaRequest request = new MfaRequest();
        if (node == null || node.isNull()) {
            return request;
        }

        if (node.has("userId") && !node.get("userId").isNull()) {
            request.setUserId(node.get("userId").asText());
        }
        if (node.has("mfaCode") && !node.get("mfaCode").isNull()) {
            request.setMfaCode(node.get("mfaCode").asText());
        }
        if (node.has("code") && !node.get("code").isNull()) {
            request.setCode(node.get("code").asText());
        }
        if (node.has("fingerprint") && !node.get("fingerprint").isNull()) {
            request.setFingerprint(node.get("fingerprint").asText());
        }

        JsonNode dmNode = null;
        if (node.has("deviceMetadata") && !node.get("deviceMetadata").isNull()) {
            dmNode = node.get("deviceMetadata");
        } else if (node.has("metadata") && !node.get("metadata").isNull()) {
            dmNode = node.get("metadata");
        }

        if (dmNode != null && dmNode.isObject()) {
            DeviceMetadata dm = new DeviceMetadata();
            if (dmNode.has("fingerprint") && !dmNode.get("fingerprint").isNull()) {
                dm.setFingerprint(dmNode.get("fingerprint").asText());
            }
            if (dmNode.has("deviceId") && !dmNode.get("deviceId").isNull()) {
                dm.setDeviceId(dmNode.get("deviceId").asText());
            }
            if (dmNode.has("userAgent") && !dmNode.get("userAgent").isNull()) {
                dm.setUserAgent(dmNode.get("userAgent").asText());
            }
            if (dmNode.has("ipAddress") && !dmNode.get("ipAddress").isNull()) {
                dm.setIpAddress(dmNode.get("ipAddress").asText());
            }
            request.setDeviceMetadata(dm);
        }

        return request;
    }

    public DeviceMetadata resolveDeviceMetadata() {
        if (deviceMetadata != null) {
            return deviceMetadata;
        }
        return metadata;
    }

    public String resolveFingerprint() {
        if (deviceMetadata != null) {
            return deviceMetadata.getFingerprint();
        }
        if (metadata != null) {
            return metadata.getFingerprint();
        }
        return fingerprint;
    }

    public String resolveCode() {
        if (mfaCode != null) {
            return mfaCode;
        }
        return code;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMfaCode() {
        return mfaCode;
    }

    public void setMfaCode(String mfaCode) {
        this.mfaCode = mfaCode;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public DeviceMetadata getDeviceMetadata() {
        return deviceMetadata;
    }

    public void setDeviceMetadata(DeviceMetadata deviceMetadata) {
        this.deviceMetadata = deviceMetadata;
    }

    public DeviceMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(DeviceMetadata metadata) {
        this.metadata = metadata;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }
}
