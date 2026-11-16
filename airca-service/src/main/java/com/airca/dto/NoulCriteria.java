package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NoulCriteria(
        @JsonProperty("true_description") String trueDescription,
        @JsonProperty("false_description") String falseDescription) {

    public NoulCriteria(String trueDescription) {
        this(trueDescription, null);
    }

    public static NoulCriteria of(String trueDescription) {
        return new NoulCriteria(trueDescription, null);
    }

    public static NoulCriteria of(String trueDescription, String falseDescription) {
        return new NoulCriteria(trueDescription, falseDescription);
    }
}
