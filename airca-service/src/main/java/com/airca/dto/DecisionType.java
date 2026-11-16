package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum DecisionType {
    NOUL("noul"),
    CHOICE("choice"),
    SCORE("score");

    private final String value;

    DecisionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DecisionType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (DecisionType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown decision type: " + value);
    }
}
