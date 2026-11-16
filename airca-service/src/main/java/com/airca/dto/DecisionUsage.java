package com.airca.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionUsage(
        @JsonProperty("total_input_tokens") Integer totalInputTokens,
        @JsonProperty("total_output_tokens") Integer totalOutputTokens,
        @JsonProperty("total_tokens") Integer totalTokens,
        @JsonProperty("input_tokens_by_modality") List<DecisionModalityTokens> inputTokensByModality) {

    public DecisionUsage {
        inputTokensByModality = inputTokensByModality != null ? List.copyOf(inputTokensByModality) : List.of();
    }
}
