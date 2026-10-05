package io.github.bereket1144.agentcontroller.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The model client of live mode. A profile decides which model is called, so this sends each call to the client for
 * its kind: the hosted client when the profile has an {@code api-key}, the local Ollama client when it has none.
 * Callers inject one {@link ModelClient} and need not know which kind a profile is.
 */
@Component
@Primary
@ConditionalOnProperty(name = "agent.model-client.mode", havingValue = "live", matchIfMissing = true)
public class LiveModelClient implements ModelClient {

    private final OllamaModelClient local;
    private final HostedModelClient hosted;

    public LiveModelClient(OllamaModelClient local, HostedModelClient hosted) {
        this.local = local;
        this.hosted = hosted;
    }

    @Override
    public ModelResult call(String role, ModelProfile profile, String systemText, String userText) {
        // A missing profile goes to the local client, which refuses it with the same message as before.
        boolean keyed = profile != null && profile.apiKey() != null;
        return (keyed ? hosted : local).call(role, profile, systemText, userText);
    }
}
