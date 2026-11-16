package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = NoulQuestion.class, name = "noul"),
        @JsonSubTypes.Type(value = ChoiceQuestion.class, name = "choice"),
        @JsonSubTypes.Type(value = ScoreQuestion.class, name = "score")
})
public interface DecisionQuestion {

    String type();

    String instructions();
}
