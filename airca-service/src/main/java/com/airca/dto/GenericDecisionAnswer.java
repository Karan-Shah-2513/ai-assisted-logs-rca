package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenericDecisionAnswer(
        @JsonProperty("type") String type,
        @JsonProperty("confidence") Double confidence) implements DecisionAnswer {

    public GenericDecisionAnswer {
        if (type == null || type.isBlank()) {
            type = "unknown";
        }
    }
}
