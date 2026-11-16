package com.airca.ai;

import com.airca.dto.ChoiceAnswer;
import com.airca.dto.ChoiceQuestion;
import com.airca.dto.DecisionAnswer;
import com.airca.dto.DecisionModalityTokens;
import com.airca.dto.DecisionQuestion;
import com.airca.dto.DecisionRequest;
import com.airca.dto.DecisionResponse;
import com.airca.dto.DecisionUsage;
import com.airca.dto.GenericDecisionAnswer;
import com.airca.dto.NoulAnswer;
import com.airca.dto.NoulQuestion;
import com.airca.dto.ScoreAnswer;
import com.airca.dto.ScoreQuestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for the official Gemini Decisions API (v1beta).
 *
 * <p>Official contract:
 * POST https://generativelanguage.googleapis.com/v1beta/decisions
 * Headers: x-goog-api-key: &lt;key&gt;, Content-Type: application/json
 * Payload: {"model": "...", "input": ..., "questions": {...}}
 *
 * <p>Includes deterministic offline fallback when:
 * <ul>
 *   <li>The API key is null, blank, or dummy</li>
 *   <li>The decision feature is explicitly disabled</li>
 *   <li>Network connectivity or API calls fail (4xx, 5xx, timeouts, connection refused)</li>
 *   <li>The API returns empty or unparseable responses</li>
 * </ul>
 */
@Component
public class GeminiDecisionClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeminiDecisionClient.class);
    public static final String API_KEY_HEADER = "x-goog-api-key";

    private final DecisionProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public GeminiDecisionClient(DecisionProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, (RestClient.Builder) null);
    }

    public GeminiDecisionClient(
            DecisionProperties properties,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder) {
        this.properties = properties != null ? properties : new DecisionProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        RestClient.Builder builder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
        this.restClient = buildRestClient(this.properties, builder);
    }

    public GeminiDecisionClient(DecisionProperties properties) {
        this(properties, new ObjectMapper(), RestClient.builder());
    }

    public GeminiDecisionClient(
            DecisionProperties properties,
            ObjectMapper objectMapper,
            RestClient restClient) {
        this.properties = properties != null ? properties : new DecisionProperties();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.restClient = restClient != null
                ? restClient
                : buildRestClient(this.properties, RestClient.builder());
    }

    private static RestClient buildRestClient(DecisionProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutSeconds = properties != null && properties.getTimeoutSeconds() > 0
                ? properties.getTimeoutSeconds()
                : DecisionProperties.DEFAULT_TIMEOUT_SECONDS;
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        return (builder != null ? builder : RestClient.builder())
                .requestFactory(factory)
                .build();
    }

    /**
     * Executes a Decisions API request with fallback on failure or unconfigured API key.
     *
     * @param request the decision request payload
     * @return DecisionResponse from the API or deterministic fallback
     */
    public DecisionResponse decide(DecisionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DecisionRequest must not be null");
        }

        String apiKey = properties.getApiKey();
        if (!properties.isEnabled() || isDummyOrEmptyApiKey(apiKey)) {
            LOGGER.debug("Gemini Decisions API unconfigured, dummy key, or disabled; using deterministic fallback");
            return deterministicFallback(request);
        }

        String effectiveModel = request.model() != null && !request.model().isBlank()
                ? request.model().trim()
                : (properties.getModel() != null && !properties.getModel().isBlank()
                        ? properties.getModel().trim()
                        : DecisionProperties.DEFAULT_MODEL);

        DecisionRequest payload = DecisionRequest.of(effectiveModel, request.input(), request.questions());
        String url = resolveDecisionsUrl();

        try {
            String requestJson = objectMapper.writeValueAsString(payload);
            LOGGER.debug("Submitting Decisions API request to {} with model {}", url, effectiveModel);

            String responseJson = restClient.post()
                    .uri(url)
                    .header(API_KEY_HEADER, apiKey.trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);

            if (responseJson == null || responseJson.isBlank()) {
                LOGGER.warn("Gemini Decisions API returned blank response; falling back to deterministic response");
                return deterministicFallback(request);
            }

            DecisionResponse response = objectMapper.readValue(responseJson, DecisionResponse.class);
            if (response == null || (response.answers() == null && response.promptFeedback() == null)) {
                LOGGER.warn("Gemini Decisions API returned invalid payload; falling back to deterministic response");
                return deterministicFallback(request);
            }

            return response;
        } catch (Exception ex) {
            LOGGER.warn("Gemini Decisions API call failed ({}: {}); falling back to deterministic response",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return deterministicFallback(request);
        }
    }

    /**
     * Convenience method to execute a decision query using the default configured model.
     *
     * @param input the input evidence/text/object
     * @param questions map of question IDs to questions
     * @return DecisionResponse
     */
    public DecisionResponse decide(Object input, Map<String, DecisionQuestion> questions) {
        return decide(DecisionRequest.of(properties.getModel(), input, questions));
    }

    /**
     * Constructs the full Decisions API endpoint URL from properties.
     *
     * @return full target URL, e.g. https://generativelanguage.googleapis.com/v1beta/decisions
     */
    public String resolveDecisionsUrl() {
        String endpoint = properties != null ? properties.getEndpoint() : null;
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = DecisionProperties.DEFAULT_ENDPOINT;
        }
        endpoint = endpoint.trim().replaceAll("/+$", "");

        String apiVersion = properties != null ? properties.getApiVersion() : null;
        if (apiVersion == null || apiVersion.isBlank()) {
            apiVersion = DecisionProperties.DEFAULT_API_VERSION;
        }
        apiVersion = apiVersion.trim().replaceAll("^/+|/+$", "");

        if (endpoint.endsWith("/" + apiVersion)) {
            return endpoint + "/decisions";
        }
        return endpoint + "/" + apiVersion + "/decisions";
    }

    /**
     * Produces a deterministic fallback response evaluating questions against input heuristics.
     *
     * @param request the original decision request
     * @return calibrated deterministic DecisionResponse
     */
    public DecisionResponse deterministicFallback(DecisionRequest request) {
        if (request == null) {
            return new DecisionResponse(Map.of(), createEmptyUsage(), null);
        }

        String inputText = request.input() != null
                ? String.valueOf(request.input()).toLowerCase(Locale.ROOT)
                : "";

        Map<String, DecisionAnswer> answers = new LinkedHashMap<>();
        if (request.questions() != null) {
            for (Map.Entry<String, DecisionQuestion> entry : request.questions().entrySet()) {
                String questionId = entry.getKey();
                DecisionQuestion question = entry.getValue();
                DecisionAnswer answer = fallbackForQuestion(questionId, question, inputText);
                if (answer != null) {
                    answers.put(questionId, answer);
                }
            }
        }

        return new DecisionResponse(answers, createEmptyUsage(), null);
    }

    private DecisionAnswer fallbackForQuestion(String questionId, DecisionQuestion question, String inputText) {
        if (question instanceof NoulQuestion noulQ) {
            return fallbackForNoul(questionId, noulQ, inputText);
        } else if (question instanceof ChoiceQuestion choiceQ) {
            return fallbackForChoice(questionId, choiceQ, inputText);
        } else if (question instanceof ScoreQuestion scoreQ) {
            return fallbackForScore(questionId, scoreQ, inputText);
        } else if (question != null) {
            return new GenericDecisionAnswer(question.type(), 0.5);
        }
        return null;
    }

    private DecisionAnswer fallbackForNoul(String questionId, NoulQuestion question, String input) {
        String qId = questionId != null ? questionId.toLowerCase(Locale.ROOT) : "";
        String instructions = question.instructions() != null
                ? question.instructions().toLowerCase(Locale.ROOT)
                : "";

        boolean hasErrorSign = containsAny(input,
                "error", "exception", "deadlock", "fail", "fatal", "outage", "timeout",
                "500", "502", "503", "504", "outofmemory", "oom", "nullpointer", "npe",
                "connection pool", "sql", "jdbc", "lock wait");

        boolean isNoiseQuestion = containsAny(qId, "noise", "ignore", "false_positive", "benign")
                || containsAny(instructions, "is noise", "false positive", "ignore");

        if (isNoiseQuestion) {
            double prob = hasErrorSign ? 0.05 : 0.85;
            double conf = 0.90;
            return new NoulAnswer(prob, conf);
        }

        double prob = hasErrorSign ? 0.95 : 0.15;
        double conf = hasErrorSign ? 0.92 : 0.75;
        return new NoulAnswer(prob, conf);
    }

    private DecisionAnswer fallbackForChoice(String questionId, ChoiceQuestion question, String input) {
        Map<String, String> criteria = question.criteria();
        if (criteria == null || criteria.isEmpty()) {
            return new ChoiceAnswer("unknown", 0.5, Map.of("unknown", 1.0));
        }

        String chosenKey = null;

        for (Map.Entry<String, String> entry : criteria.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            String desc = entry.getValue() != null ? entry.getValue().toLowerCase(Locale.ROOT) : "";
            if (input.contains(key) || (!desc.isEmpty() && containsAny(input, desc))) {
                chosenKey = entry.getKey();
                break;
            }
        }

        if (chosenKey == null) {
            boolean isCritical = containsAny(input, "deadlock", "503", "fatal", "outage", "p0", "oom", "critical");
            boolean isHigh = containsAny(input, "error", "exception", "failed", "timeout", "p1", "high");

            for (String key : criteria.keySet()) {
                String lowerKey = key.toLowerCase(Locale.ROOT);
                if (isCritical && (lowerKey.contains("p0") || lowerKey.contains("p1") || lowerKey.contains("critical"))) {
                    chosenKey = key;
                    break;
                } else if (isHigh && (lowerKey.contains("p2") || lowerKey.contains("high") || lowerKey.contains("warn"))) {
                    chosenKey = key;
                    break;
                }
            }
        }

        if (chosenKey == null) {
            chosenKey = criteria.keySet().iterator().next();
        }

        Map<String, Double> probs = new LinkedHashMap<>();
        int count = criteria.size();
        if (count == 1) {
            probs.put(chosenKey, 1.0);
        } else {
            double primaryProb = 0.85;
            double remaining = (1.0 - primaryProb) / (count - 1);
            for (String k : criteria.keySet()) {
                if (k.equals(chosenKey)) {
                    probs.put(k, primaryProb);
                } else {
                    probs.put(k, Math.round(remaining * 100.0) / 100.0);
                }
            }
        }

        return new ChoiceAnswer(chosenKey, 0.88, probs);
    }

    private DecisionAnswer fallbackForScore(String questionId, ScoreQuestion question, String input) {
        List<String> criteria = question.criteria();
        Map<String, String> legend = new LinkedHashMap<>();
        int count = criteria != null ? criteria.size() : 0;
        for (int i = 0; i < count; i++) {
            legend.put(String.valueOf(i), criteria.get(i));
        }

        if (count == 0) {
            return new ScoreAnswer(0.0, 0.5, Map.of("0", 1.0), Map.of());
        }

        int selectedIndex;
        boolean isCritical = containsAny(input, "deadlock", "503", "fatal", "outage", "p0", "oom", "critical");
        boolean isHigh = containsAny(input, "error", "exception", "failed", "timeout", "high");

        if (isCritical) {
            selectedIndex = count - 1;
        } else if (isHigh) {
            selectedIndex = Math.max(0, count / 2);
        } else {
            selectedIndex = 0;
        }

        Map<String, Double> probs = new LinkedHashMap<>();
        if (count == 1) {
            probs.put("0", 1.0);
        } else {
            double primaryProb = 0.85;
            double remaining = (1.0 - primaryProb) / (count - 1);
            for (int i = 0; i < count; i++) {
                String key = String.valueOf(i);
                if (i == selectedIndex) {
                    probs.put(key, primaryProb);
                } else {
                    probs.put(key, Math.round(remaining * 100.0) / 100.0);
                }
            }
        }

        return new ScoreAnswer((double) selectedIndex, 0.85, probs, legend);
    }

    private static DecisionUsage createEmptyUsage() {
        return new DecisionUsage(0, 0, 0, List.of(new DecisionModalityTokens("text", 0)));
    }

    private static boolean containsAny(String text, String... words) {
        if (text == null || words == null) {
            return false;
        }
        for (String word : words) {
            if (word != null && !word.isBlank() && text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the configured API key is null, blank, or placeholder/dummy.
     */
    public static boolean isDummyOrEmptyApiKey(String apiKey) {
        if (apiKey == null) {
            return true;
        }
        String trimmed = apiKey.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.equals("dummy")
                || lower.equals("dummy-key")
                || lower.equals("dummy_key")
                || lower.equals("dummy-api-key")
                || lower.startsWith("dummy")
                || lower.equals("placeholder")
                || lower.equals("none")
                || lower.equals("null");
    }

    public DecisionProperties getProperties() {
        return properties;
    }
}
