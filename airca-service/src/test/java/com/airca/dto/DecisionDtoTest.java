package com.airca.dto;

import com.airca.ai.DecisionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Should serialize DecisionRequest matching the official Gemini Decisions API schema")
    void shouldSerializeDecisionRequestMatchingApiSchema() throws Exception {
        DecisionRequest request = DecisionRequest.builder()
                .model("gemini-decision-1")
                .input("Customer message: Production API requests have been failing with 503 errors for the past 20 minutes, blocking all checkout traffic!")
                .question("is_outage", NoulQuestion.of(
                        "Determine whether the customer is reporting an active production outage.",
                        "The report indicates an active high-severity production outage.",
                        "The report is routine inquiry, test error, or non-production alert."))
                .question("priority", ChoiceQuestion.of(
                        "Select the incident priority level.",
                        Map.of(
                                "p1_critical", "Production outage blocking core business traffic.",
                                "p2_high", "Major feature degraded with a workaround available.",
                                "p3_normal", "Minor bug or general question.")))
                .question("urgency", ScoreQuestion.of(
                        "Rate the urgency of immediate escalation.",
                        List.of(
                                "Routine inquiry, standard SLA.",
                                "Time-sensitive issue needing same-day follow-up.",
                                "Immediate escalation required for active production impact.")))
                .build();

        String json = objectMapper.writeValueAsString(request);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("model").asText()).isEqualTo("gemini-decision-1");
        assertThat(root.get("input").asText()).contains("Production API requests have been failing");

        JsonNode questions = root.get("questions");
        assertThat(questions).isNotNull();

        // Noul question verification
        JsonNode noul = questions.get("is_outage");
        assertThat(noul.get("type").asText()).isEqualTo("noul");
        assertThat(noul.get("instructions").asText()).contains("active production outage");
        assertThat(noul.get("criteria").get("true_description").asText()).contains("active high-severity");
        assertThat(noul.get("criteria").get("false_description").asText()).contains("routine inquiry");

        // Choice question verification
        JsonNode choice = questions.get("priority");
        assertThat(choice.get("type").asText()).isEqualTo("choice");
        assertThat(choice.get("instructions").asText()).contains("priority level");
        assertThat(choice.get("criteria").get("p1_critical").asText()).contains("blocking core business");

        // Score question verification
        JsonNode score = questions.get("urgency");
        assertThat(score.get("type").asText()).isEqualTo("score");
        assertThat(score.get("instructions").asText()).contains("urgency");
        assertThat(score.get("criteria").isArray()).isTrue();
        assertThat(score.get("criteria").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should deserialize DecisionRequest from JSON accurately")
    void shouldDeserializeDecisionRequest() throws Exception {
        String json = """
                {
                  "model": "gemini-decision-1",
                  "input": "Error: NullPointerException in PaymentProcessor line 42",
                  "questions": {
                    "is_critical": {
                      "type": "noul",
                      "instructions": "Is this a critical exception?"
                    },
                    "category": {
                      "type": "choice",
                      "instructions": "Classify exception",
                      "criteria": {
                        "db": "Database error",
                        "app": "Application error"
                      }
                    },
                    "severity_level": {
                      "type": "score",
                      "instructions": "Score severity 0 to 2",
                      "criteria": [
                        "Low",
                        "Medium",
                        "High"
                      ]
                    }
                  }
                }
                """;

        DecisionRequest request = objectMapper.readValue(json, DecisionRequest.class);
        assertThat(request.model()).isEqualTo("gemini-decision-1");
        assertThat(request.input()).isEqualTo("Error: NullPointerException in PaymentProcessor line 42");
        assertThat(request.questions()).hasSize(3);

        DecisionQuestion q1 = request.questions().get("is_critical");
        assertThat(q1).isInstanceOf(NoulQuestion.class);
        assertThat(q1.type()).isEqualTo("noul");

        DecisionQuestion q2 = request.questions().get("category");
        assertThat(q2).isInstanceOf(ChoiceQuestion.class);
        ChoiceQuestion choiceQ = (ChoiceQuestion) q2;
        assertThat(choiceQ.criteria()).containsEntry("db", "Database error");

        DecisionQuestion q3 = request.questions().get("severity_level");
        assertThat(q3).isInstanceOf(ScoreQuestion.class);
        ScoreQuestion scoreQ = (ScoreQuestion) q3;
        assertThat(scoreQ.criteria()).containsExactly("Low", "Medium", "High");
    }

    @Test
    @DisplayName("Should deserialize DecisionResponse from official API response JSON")
    void shouldDeserializeDecisionResponseFromOfficialApiPayload() throws Exception {
        String responseJson = """
                {
                  "answers": {
                    "is_outage": {
                      "type": "noul",
                      "confidence": 0.98,
                      "noul": 0.98
                    },
                    "priority": {
                      "type": "choice",
                      "choice": "p1_critical",
                      "confidence": 0.93,
                      "probabilities": {
                        "p1_critical": 0.93,
                        "p2_high": 0.06,
                        "p3_normal": 0.01
                      }
                    },
                    "urgency": {
                      "type": "score",
                      "score": 1.9,
                      "confidence": 0.91,
                      "legend": {
                        "0": "Routine inquiry, standard SLA.",
                        "1": "Time-sensitive issue needing same-day follow-up.",
                        "2": "Immediate escalation required for active production impact."
                      },
                      "probabilities": {
                        "0": 0.01,
                        "1": 0.08,
                        "2": 0.91
                      }
                    }
                  },
                  "usage": {
                    "input_tokens_by_modality": [
                      {
                        "modality": "text",
                        "tokens": 152
                      }
                    ],
                    "total_input_tokens": 152,
                    "total_output_tokens": 0,
                    "total_tokens": 152
                  },
                  "prompt_feedback": {
                    "block_reason": null,
                    "block_reason_message": null
                  }
                }
                """;

        DecisionResponse response = objectMapper.readValue(responseJson, DecisionResponse.class);
        assertThat(response).isNotNull();
        assertThat(response.isBlocked()).isFalse();

        // Test NoulAnswer
        NoulAnswer noulAnswer = response.getNoulAnswer("is_outage");
        assertThat(noulAnswer).isNotNull();
        assertThat(noulAnswer.type()).isEqualTo("noul");
        assertThat(noulAnswer.probability()).isEqualTo(0.98);
        assertThat(noulAnswer.confidenceScore()).isEqualTo(0.98);
        assertThat(noulAnswer.isAffirmative()).isTrue();
        assertThat(noulAnswer.isAffirmative(0.99)).isFalse();

        // Test ChoiceAnswer
        ChoiceAnswer choiceAnswer = response.getChoiceAnswer("priority");
        assertThat(choiceAnswer).isNotNull();
        assertThat(choiceAnswer.type()).isEqualTo("choice");
        assertThat(choiceAnswer.choice()).isEqualTo("p1_critical");
        assertThat(choiceAnswer.confidenceScore()).isEqualTo(0.93);
        assertThat(choiceAnswer.probability("p1_critical")).isEqualTo(0.93);
        assertThat(choiceAnswer.probability("p2_high")).isEqualTo(0.06);
        assertThat(choiceAnswer.probability("unknown")).isEqualTo(0.0);

        // Test ScoreAnswer
        ScoreAnswer scoreAnswer = response.getScoreAnswer("urgency");
        assertThat(scoreAnswer).isNotNull();
        assertThat(scoreAnswer.type()).isEqualTo("score");
        assertThat(scoreAnswer.scoreValue()).isEqualTo(1.9);
        assertThat(scoreAnswer.confidenceScore()).isEqualTo(0.91);
        assertThat(scoreAnswer.probabilities()).containsEntry("2", 0.91);
        assertThat(scoreAnswer.legend()).containsEntry("2", "Immediate escalation required for active production impact.");

        // Test Usage
        assertThat(response.usage()).isNotNull();
        assertThat(response.usage().totalInputTokens()).isEqualTo(152);
        assertThat(response.usage().totalOutputTokens()).isEqualTo(0);
        assertThat(response.usage().totalTokens()).isEqualTo(152);
        assertThat(response.usage().inputTokensByModality()).hasSize(1);
        assertThat(response.usage().inputTokensByModality().get(0).modality()).isEqualTo("text");
        assertThat(response.usage().inputTokensByModality().get(0).tokens()).isEqualTo(152);
    }

    @Test
    @DisplayName("Should detect blocked prompt in DecisionResponse")
    void shouldDetectBlockedPrompt() throws Exception {
        String blockedJson = """
                {
                  "answers": {},
                  "prompt_feedback": {
                    "block_reason": "safety",
                    "block_reason_message": "Blocked due to safety threshold trigger"
                  }
                }
                """;

        DecisionResponse response = objectMapper.readValue(blockedJson, DecisionResponse.class);
        assertThat(response.isBlocked()).isTrue();
        assertThat(response.promptFeedback().blockReason()).isEqualTo("safety");
        assertThat(response.promptFeedback().blockReasonMessage()).contains("safety threshold");
        assertThat(response.answers()).isEmpty();
    }

    @Test
    @DisplayName("Should support DecisionType enum mapping")
    void shouldSupportDecisionTypeEnum() {
        assertThat(DecisionType.fromValue("noul")).isEqualTo(DecisionType.NOUL);
        assertThat(DecisionType.fromValue("choice")).isEqualTo(DecisionType.CHOICE);
        assertThat(DecisionType.fromValue("score")).isEqualTo(DecisionType.SCORE);
        assertThat(DecisionType.NOUL.getValue()).isEqualTo("noul");
        assertThat(DecisionType.CHOICE.getValue()).isEqualTo("choice");
        assertThat(DecisionType.SCORE.getValue()).isEqualTo("score");
    }

    @Test
    @DisplayName("Should verify DecisionProperties defaults and accessors")
    void shouldVerifyDecisionProperties() {
        DecisionProperties properties = new DecisionProperties();
        assertThat(properties.getModel()).isEqualTo("gemini-decision-1");
        assertThat(properties.getEndpoint()).isEqualTo("https://generativelanguage.googleapis.com");
        assertThat(properties.getApiVersion()).isEqualTo("v1beta");
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTimeoutSeconds()).isEqualTo(10);

        DecisionProperties custom = new DecisionProperties(
                "custom-decision-model",
                "https://custom-endpoint.google.com",
                "v1",
                false,
                15,
                "secret-api-key");
        assertThat(custom.getModel()).isEqualTo("custom-decision-model");
        assertThat(custom.getEndpoint()).isEqualTo("https://custom-endpoint.google.com");
        assertThat(custom.getApiVersion()).isEqualTo("v1");
        assertThat(custom.isEnabled()).isFalse();
        assertThat(custom.getTimeoutSeconds()).isEqualTo(15);
        assertThat(custom.getApiKey()).isEqualTo("secret-api-key");
    }
}
