package io.github.bereket1144.agentcontroller.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The model client of live mode. A profile decides which model is called, so this sends each call to the client for
 * its API style: Ollama, Anthropic or an OpenAI-style API such as OpenRouter. A profile that names no style is
 * Anthropic when it has an {@code api-key} and Ollama when it has none.
 * Callers inject one {@link ModelClient} and need not know which kind a profile is.
 */
@Component
@Primary
@ConditionalOnProperty(name = "agent.model-client.mode", havingValue = "live", matchIfMissing = true)
public class LiveModelClient implements ModelClient {

    private final OllamaModelClient local;
    private final HostedModelClient hosted;
    private final OpenAiCompatibleModelClient openAi;

    public LiveModelClient(OllamaModelClient local, HostedModelClient hosted, OpenAiCompatibleModelClient openAi) {
        this.local = local;
        this.hosted = hosted;
        this.openAi = openAi;
    }

    @Override
    public ModelResult call(String role, ModelProfile profile, String systemText, String userText) {
        // A missing profile goes to the local client, which refuses it with the same message as before.
        if (profile == null) {
            return local.call(role, profile, systemText, userText);
        }
        return switch (profile.style()) {
            case OLLAMA -> local.call(role, profile, systemText, userText);
            case ANTHROPIC -> hosted.call(role, profile, systemText, userText);
            case OPENAI -> openAi.call(role, profile, systemText, userText);
        };
    }
}
