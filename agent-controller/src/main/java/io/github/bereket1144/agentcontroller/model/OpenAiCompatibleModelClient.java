package io.github.bereket1144.agentcontroller.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls a model through an OpenAI-style chat API ({@code POST <base-address>/chat/completions}), as OpenRouter
 * offers. {@link LiveModelClient} sends it the calls of profiles whose {@code api-style} is {@code openai}. The key
 * comes from the profile, which reads it from the environment; it is sent as a bearer token and never logged.
 */
@Component
@ConditionalOnProperty(name = "agent.model-client.mode", havingValue = "live", matchIfMissing = true)
public class OpenAiCompatibleModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelClient.class);
    /** A reasoning block that some models write before their answer. */
    private static final Pattern THINK_BLOCK = Pattern.compile("(?s)\\A\\s*<think>.*?</think>\\s*");

    private final ModelProfiles profiles;
    private final RoleCatalog roles;
    private final ConcurrentMap<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RestClient> clients = new ConcurrentHashMap<>();

    public OpenAiCompatibleModelClient(ModelProfiles profiles, RoleCatalog roles) {
        this.profiles = profiles;
        this.roles = roles;
    }

    @Override
    public ModelResult call(String role, ModelProfile profile, String systemText, String userText) {
        roles.get(role);
        profiles.requireConfigured(profile);
        Objects.requireNonNull(systemText, "systemText must not be null");
        Objects.requireNonNull(userText, "userText must not be null");
        if (profile.apiKey() == null || profile.apiKey().isBlank()) {
            throw new IllegalArgumentException("OpenAI-style client cannot call a profile without a key");
        }

        Semaphore permit = permits.computeIfAbsent(profile.name(), ignored ->
                new Semaphore(profile.maxConcurrentCalls(), true));
        try {
            // Waiting for a slot does not spend the HTTP call's timeout.
            permit.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to call model profile " + profile.name());
        }

        long started = System.nanoTime();
        String outcome = "UNEXPECTED";
        int answerChars = 0;
        try {
            RestClient client = clients.computeIfAbsent(profile.name(), ignored -> OllamaModelClient.clientFor(profile));
            Map<String, Object> request = new java.util.LinkedHashMap<>(Map.of(
                    "model", profile.model(),
                    "max_tokens", profile.answerLimit(),
                    "temperature", profile.temperature(),
                    "messages", List.of(Map.of("role", "system", "content", systemText),
                            Map.of("role", "user", "content", userText))));
            if (profile.reasoning() != null) {
                // OpenRouter's switch for hidden reasoning; false keeps the answer limit for the answer itself.
                request.put("reasoning", Map.of("enabled", profile.reasoning()));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.post().uri("/chat/completions")
                    .header("Authorization", "Bearer " + profile.apiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Map.class);
            ModelResult result = readResponse(profile, response, Duration.ofNanos(System.nanoTime() - started));
            answerChars = result.text().length();
            outcome = "SUCCESS";
            return result;
        } catch (RestClientResponseException exception) {
            FailureReason reason = OllamaModelClient.statusFailure(exception.getStatusCode().value());
            outcome = reason.name();
            throw new ModelCallException(reason, profile.name());
        } catch (ResourceAccessException exception) {
            FailureReason reason = OllamaModelClient.timedOut(exception)
                    ? FailureReason.TIMED_OUT : FailureReason.UNREACHABLE;
            outcome = reason.name();
            throw new ModelCallException(reason, profile.name());
        } catch (RestClientException exception) {
            outcome = FailureReason.RESPONSE_UNREADABLE.name();
            throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        } catch (ModelCallException exception) {
            outcome = exception.reason().name();
            throw exception;
        } finally {
            permit.release();
            log.info("event=model_call role={} profile={} requestChars={} answerChars={} outcome={} durationMs={}",
                    role, profile.name(), (long) systemText.length() + userText.length(), answerChars,
                    outcome, Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
    }

    private static ModelResult readResponse(ModelProfile profile, Map<String, Object> response, Duration duration) {
        if (response == null) {
            throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        }
        if (response.get("error") instanceof Map<?, ?> error) {
            // OpenRouter can report a failure inside a 200 reply; its code is an HTTP-like number.
            throw new ModelCallException(error.get("code") instanceof Number code
                    ? OllamaModelClient.statusFailure(code.intValue()) : FailureReason.RESPONSE_UNREADABLE,
                    profile.name());
        }
        if (!(response.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.getFirst() instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String content)
                || !(choice.get("finish_reason") instanceof String finishReason)) {
            throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        }
        FinishReason finish = switch (finishReason) {
            case "stop" -> FinishReason.COMPLETE;
            case "length" -> FinishReason.ANSWER_LIMIT;
            default -> throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        };
        String text = THINK_BLOCK.matcher(content).replaceFirst("");
        if (text.isBlank()) {
            throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        }
        Map<?, ?> usage = response.get("usage") instanceof Map<?, ?> reported ? reported : Map.of();
        return new ModelResult(text, finish,
                OllamaModelClient.tokenCount(profile, usage, "prompt_tokens"),
                OllamaModelClient.tokenCount(profile, usage, "completion_tokens"), duration);
    }
}
