package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionRequest(
        @JsonProperty("model") String model,
        @JsonProperty("input") Object input,
        @JsonProperty("questions") Map<String, DecisionQuestion> questions) {

    public DecisionRequest {
        questions = questions != null ? Map.copyOf(questions) : Map.of();
    }

    public static DecisionRequest of(String model, Object input, Map<String, DecisionQuestion> questions) {
        return new DecisionRequest(model, input, questions);
    }

    public static DecisionRequest of(String model, String input, Map<String, DecisionQuestion> questions) {
        return new DecisionRequest(model, (Object) input, questions);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model = "gemini-decision-1";
        private Object input;
        private final Map<String, DecisionQuestion> questions = new LinkedHashMap<>();

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder input(Object input) {
            this.input = input;
            return this;
        }

        public Builder question(String questionId, DecisionQuestion question) {
            if (questionId != null && question != null) {
                this.questions.put(questionId, question);
            }
            return this;
        }

        public Builder questions(Map<String, ? extends DecisionQuestion> questions) {
            if (questions != null) {
                this.questions.putAll(questions);
            }
            return this;
        }

        public DecisionRequest build() {
            return new DecisionRequest(model, input, questions);
        }
    }
}
