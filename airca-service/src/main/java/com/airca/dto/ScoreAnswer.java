package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScoreAnswer(
        @JsonProperty("type") String type,
        @JsonProperty("score") Double score,
        @JsonProperty("confidence") Double confidence,
        @JsonProperty("probabilities") Map<String, Double> probabilities,
        @JsonProperty("legend") Map<String, String> legend) implements DecisionAnswer {

    public ScoreAnswer {
        if (type == null || type.isBlank()) {
            type = "score";
        }
        probabilities = probabilities != null ? Map.copyOf(probabilities) : Map.of();
        legend = legend != null ? Map.copyOf(legend) : Map.of();
    }

    public ScoreAnswer(Double score, Double confidence, Map<String, Double> probabilities, Map<String, String> legend) {
        this("score", score, confidence, probabilities, legend);
    }

    public double scoreValue() {
        return score != null ? score : 0.0;
    }

    public double confidenceScore() {
        return confidence != null ? confidence : 0.0;
    }
}
