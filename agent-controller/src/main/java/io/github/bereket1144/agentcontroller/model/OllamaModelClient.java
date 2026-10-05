package io.github.bereket1144.agentcontroller.model;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
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
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Calls the configured local Ollama API without exposing its protocol to SDLC roles. */
@Component
public class OllamaModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaModelClient.class);

    private final ModelProfiles profiles;
    private final RoleCatalog roles;
    private final ConcurrentMap<String, Semaphore> permits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RestClient> clients = new ConcurrentHashMap<>();

    public OllamaModelClient(ModelProfiles profiles, RoleCatalog roles) {
        this.profiles = profiles;
        this.roles = roles;
    }

    @Override
    public ModelResult call(String role, ModelProfile profile, String systemText, String userText) {
        roles.get(role);
        profiles.requireConfigured(profile);
        Objects.requireNonNull(systemText, "systemText must not be null");
        Objects.requireNonNull(userText, "userText must not be null");
        if (profile.apiKey() != null) {
            throw new IllegalArgumentException("Ollama client cannot call a keyed profile");
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
            RestClient client = clients.computeIfAbsent(profile.name(), ignored -> clientFor(profile));
            Map<String, Object> request = Map.of(
                    "model", profile.model(),
                    "messages", List.of(Map.of("role", "system", "content", systemText),
                            Map.of("role", "user", "content", userText)),
                    "stream", false,
                    "options", Map.of("num_ctx", profile.contextWindow(), "num_predict", profile.answerLimit(),
                            "temperature", profile.temperature()));
            @SuppressWarnings("unchecked")
            Map<String, Object> response = client.post().uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Map.class);
            ModelResult result = readResponse(profile, response, Duration.ofNanos(System.nanoTime() - started));
            answerChars = result.text().length();
            outcome = "SUCCESS";
            return result;
        } catch (RestClientResponseException exception) {
            FailureReason reason = statusFailure(exception.getStatusCode().value());
            outcome = reason.name();
            throw new ModelCallException(reason, profile.name());
        } catch (ResourceAccessException exception) {
            FailureReason reason = timedOut(exception) ? FailureReason.TIMED_OUT : FailureReason.UNREACHABLE;
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

    private static RestClient clientFor(ModelProfile profile) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(profile.callTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(httpClient);
        requests.setReadTimeout(profile.callTimeout());
        return RestClient.builder().baseUrl(profile.baseAddress().toString())
                .requestFactory(requests).build();
    }

    private static ModelResult readResponse(ModelProfile profile, Map<String, Object> response,
                                            Duration duration) {
        if (response == null || !Boolean.TRUE.equals(response.get("done"))
                || !(response.get("message") instanceof Map<?, ?> message)) {
            throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        }
        if (!(message.get("content") instanceof String text)
                || !(response.get("done_reason") instanceof String doneReason)) {
            throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        }
        FinishReason finish = switch (doneReason) {
            case "stop" -> FinishReason.COMPLETE;
            case "length" -> FinishReason.ANSWER_LIMIT;
            default -> throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
        };
        return new ModelResult(text, finish,
                tokenCount(profile, response, "prompt_eval_count"),
                tokenCount(profile, response, "eval_count"), duration);
    }

    private static Long tokenCount(ModelProfile profile, Map<String, Object> response, String field) {
        Object value = response.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer count && count >= 0) {
            return count.longValue();
        }
        if (value instanceof Long count && count >= 0) {
            return count;
        }
        throw new ModelCallException(FailureReason.RESPONSE_UNREADABLE, profile.name());
    }

    private static FailureReason statusFailure(int status) {
        if (status == 408) {
            return FailureReason.TIMED_OUT;
        }
        if (status == 429) {
            return FailureReason.RATE_LIMITED;
        }
        return status >= 500 ? FailureReason.SERVER_ERROR : FailureReason.REQUEST_REJECTED;
    }

    static boolean timedOut(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            if (current instanceof ConnectException) {
                return false;
            }
        }
        return false;
    }
}
