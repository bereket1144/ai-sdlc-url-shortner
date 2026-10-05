package io.github.bereket1144.agentcontroller.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

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
 * Calls a hosted model through its messages API ({@code POST <base-address>/v1/messages}) without exposing the
 * protocol to SDLC roles. {@link LiveModelClient} sends it the calls of profiles that have an {@code api-key}. The
 * key comes from the profile, which reads it from the environment; it is sent as a header and never logged.
 */
@Component
@ConditionalOnProperty(name = "agent.model-client.mode", havingValue = "live", matchIfMissing = true)
public class HostedModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(HostedModelClient.class);

    private static final String API_VERSION = "2023-06-01";

    private final ModelProfiles profiles;
    private final RoleCatalog roles;
    private final ConcurrentMap<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RestClient> clients = new ConcurrentHashMap<>();

    public HostedModelClient(ModelProfiles profiles, RoleCatalog roles) {
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
            throw new IllegalArgumentException("Hosted client cannot call a profile without a key");
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
            Map<String, Object> request = Map.of(
                    "model", profile.model(),
                    "max_tokens", profile.answerLimit(),
                    "temperature", profile.temperature(),
                    "system", systemText,
                    "messages", List.of(Map.of("role", "user", "content", userText)));
            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.post().uri("/v1/messages")
                    .header("x-api-key", profile.apiKey())
                    .header("anthropic-version", API_VERSION)
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

    private static ModelResult readResponse(ModelProfile profile, Map<String, Object> response,
                                            Duration duration) {
        if (response == null || !(response.get("content") instanceof List<?> blocks)
                || !(response.get("stop_reason") instanceof String stopReason)) {
            throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        }
        FinishReason finish = switch (stopReason) {
            case "end_turn", "stop_sequence" -> FinishReason.COMPLETE;
            case "max_tokens" -> FinishReason.ANSWER_LIMIT;
            default -> throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        };
        List<String> texts = blocks.stream()
                .filter(block -> block instanceof Map<?, ?> map && "text".equals(map.get("type")))
                .map(block -> ((Map<?, ?>) block).get("text"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        if (texts.isEmpty()) {
            throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        }
        Map<?, ?> usage = response.get("usage") instanceof Map<?, ?> reported ? reported : Map.of();
        return new ModelResult(String.join("", texts), finish,
                OllamaModelClient.tokenCount(profile, usage, "input_tokens"),
                OllamaModelClient.tokenCount(profile, usage, "output_tokens"), duration);
    }
}
