package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChoiceQuestion(
        @JsonProperty("type") String type,
        @JsonProperty("instructions") String instructions,
        @JsonProperty("criteria") Map<String, String> criteria) implements DecisionQuestion {

    public ChoiceQuestion {
        if (type == null || type.isBlank()) {
            type = "choice";
        }
        criteria = criteria != null ? Map.copyOf(criteria) : Map.of();
    }

    public ChoiceQuestion(String instructions, Map<String, String> criteria) {
        this("choice", instructions, criteria);
    }

    public static ChoiceQuestion of(String instructions, Map<String, String> criteria) {
        return new ChoiceQuestion(instructions, criteria);
    }
}
