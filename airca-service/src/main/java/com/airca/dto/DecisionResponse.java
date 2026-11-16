package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionResponse(
        @JsonProperty("answers") Map<String, DecisionAnswer> answers,
        @JsonProperty("usage") DecisionUsage usage,
        @JsonProperty("prompt_feedback") DecisionPromptFeedback promptFeedback) {

    public DecisionResponse {
        answers = answers != null ? Map.copyOf(answers) : Map.of();
    }

    public DecisionAnswer getAnswer(String questionId) {
        return answers.get(questionId);
    }

    public boolean hasAnswer(String questionId) {
        return answers.containsKey(questionId);
    }

    public NoulAnswer getNoulAnswer(String questionId) {
        DecisionAnswer answer = answers.get(questionId);
        return answer instanceof NoulAnswer noulAnswer ? noulAnswer : null;
    }

    public ChoiceAnswer getChoiceAnswer(String questionId) {
        DecisionAnswer answer = answers.get(questionId);
        return answer instanceof ChoiceAnswer choiceAnswer ? choiceAnswer : null;
    }

    public ScoreAnswer getScoreAnswer(String questionId) {
        DecisionAnswer answer = answers.get(questionId);
        return answer instanceof ScoreAnswer scoreAnswer ? scoreAnswer : null;
    }

    public boolean isBlocked() {
        return promptFeedback != null && promptFeedback.isBlocked();
    }
}
