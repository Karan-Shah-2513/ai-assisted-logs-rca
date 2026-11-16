package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScoreQuestion(
        @JsonProperty("type") String type,
        @JsonProperty("instructions") String instructions,
        @JsonProperty("criteria") List<String> criteria) implements DecisionQuestion {

    public ScoreQuestion {
        if (type == null || type.isBlank()) {
            type = "score";
        }
        criteria = criteria != null ? List.copyOf(criteria) : List.of();
    }

    public ScoreQuestion(String instructions, List<String> criteria) {
        this("score", instructions, criteria);
    }

    public static ScoreQuestion of(String instructions, List<String> criteria) {
        return new ScoreQuestion(instructions, criteria);
    }

    public static ScoreQuestion of(String instructions, String... criteria) {
        return new ScoreQuestion(instructions, criteria != null ? List.of(criteria) : List.of());
    }
}
