package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionPromptFeedback(
        @JsonProperty("block_reason") String blockReason,
        @JsonProperty("block_reason_message") String blockReasonMessage) {

    public boolean isBlocked() {
        return blockReason != null && !blockReason.isBlank();
    }
}
