package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NoulAnswer(
        @JsonProperty("type") String type,
        @JsonProperty("noul") Double noul,
        @JsonProperty("confidence") Double confidence) implements DecisionAnswer {

    public NoulAnswer {
        if (type == null || type.isBlank()) {
            type = "noul";
        }
    }

    public NoulAnswer(Double noul, Double confidence) {
        this("noul", noul, confidence);
    }

    public double probability() {
        return noul != null ? noul : 0.0;
    }

    public double confidenceScore() {
        return confidence != null ? confidence : 0.0;
    }

    public boolean isAffirmative() {
        return isAffirmative(0.5);
    }

    public boolean isAffirmative(double threshold) {
        return probability() >= threshold;
    }
}
