package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NoulQuestion(
        @JsonProperty("type") String type,
        @JsonProperty("instructions") String instructions,
        @JsonProperty("criteria") NoulCriteria criteria) implements DecisionQuestion {

    public NoulQuestion {
        if (type == null || type.isBlank()) {
            type = "noul";
        }
    }

    public NoulQuestion(String instructions, NoulCriteria criteria) {
        this("noul", instructions, criteria);
    }

    public NoulQuestion(String instructions) {
        this("noul", instructions, null);
    }

    public static NoulQuestion of(String instructions) {
        return new NoulQuestion(instructions);
    }

    public static NoulQuestion of(String instructions, NoulCriteria criteria) {
        return new NoulQuestion(instructions, criteria);
    }

    public static NoulQuestion of(String instructions, String trueDescription, String falseDescription) {
        return new NoulQuestion(instructions, new NoulCriteria(trueDescription, falseDescription));
    }
}
