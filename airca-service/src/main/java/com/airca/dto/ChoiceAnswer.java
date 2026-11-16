package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChoiceAnswer(
        @JsonProperty("type") String type,
        @JsonProperty("choice") String choice,
        @JsonProperty("confidence") Double confidence,
        @JsonProperty("probabilities") Map<String, Double> probabilities) implements DecisionAnswer {

    public ChoiceAnswer {
        if (type == null || type.isBlank()) {
            type = "choice";
        }
        probabilities = probabilities != null ? Map.copyOf(probabilities) : Map.of();
    }

    public ChoiceAnswer(String choice, Double confidence, Map<String, Double> probabilities) {
        this("choice", choice, confidence, probabilities);
    }

    public double confidenceScore() {
        return confidence != null ? confidence : 0.0;
    }

    public double probability(String option) {
        if (probabilities == null || option == null) {
            return 0.0;
        }
        Double p = probabilities.get(option);
        return p != null ? p : 0.0;
    }
}
