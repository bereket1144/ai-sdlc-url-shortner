package io.github.bereket1144.agentcontroller.model;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * One model and the limits the platform keeps when it calls it. It is read from
 * {@code agent.models.profiles.<name>.*}; {@code <name>} becomes {@link #name()}.
 *
 * @param name               the profile's key in the configuration (set by {@link ModelProperties}, not bound)
 * @param model              the identifier the model server knows the model by
 * @param baseAddress        where the model server listens
 * @param contextWindow      the most tokens a prompt and its answer can use together
 * @param answerLimit        the most tokens the model may write in one answer
 * @param callTimeout        how long one call may run once it has started
 * @param maxConcurrentCalls how many calls to this model may run at the same time
 * @param retryLimit         how many times a failed call may be tried again
 * @param temperature        the sampling temperature; 0 asks for the most repeatable answer
 * @param apiKey             the key of a hosted model, or null for a local one; read from the environment only
 * @param apiStyle           the kind of API the model server speaks, or null to infer it from the key
 * @param reasoning          false asks an OpenAI-style server not to spend tokens on hidden reasoning; null sends
 *                           nothing and leaves the model's default
 */
public record ModelProfile(
        String name,
        String model,
        URI baseAddress,
        int contextWindow,
        int answerLimit,
        Duration callTimeout,
        @DefaultValue("1") int maxConcurrentCalls,
        @DefaultValue("2") int retryLimit,
        @DefaultValue("0") double temperature,
        String apiKey,
        ApiStyle apiStyle,
        Boolean reasoning) {

    /** The protocol a model server speaks: Ollama's chat API, Anthropic's messages API or an OpenAI-style chat API. */
    public enum ApiStyle { OLLAMA, ANTHROPIC, OPENAI }

    /** Binds the settings; the shorter constructor below is for code that does not name a style. */
    @ConstructorBinding
    public ModelProfile {
    }

    /** A profile that names no style: it is inferred from the key, as before. */
    public ModelProfile(String name, String model, URI baseAddress, int contextWindow, int answerLimit,
                        Duration callTimeout, int maxConcurrentCalls, int retryLimit, double temperature,
                        String apiKey) {
        this(name, model, baseAddress, contextWindow, answerLimit, callTimeout, maxConcurrentCalls, retryLimit,
                temperature, apiKey, null, null);
    }

    /** A profile that names a style and leaves the reasoning setting alone. */
    public ModelProfile(String name, String model, URI baseAddress, int contextWindow, int answerLimit,
                        Duration callTimeout, int maxConcurrentCalls, int retryLimit, double temperature,
                        String apiKey, ApiStyle apiStyle) {
        this(name, model, baseAddress, contextWindow, answerLimit, callTimeout, maxConcurrentCalls, retryLimit,
                temperature, apiKey, apiStyle, null);
    }

    /** The named style, or when none is named: a profile with a key is Anthropic, one without is Ollama. */
    public ApiStyle style() {
        if (apiStyle != null) {
            return apiStyle;
        }
        return apiKey != null ? ApiStyle.ANTHROPIC : ApiStyle.OLLAMA;
    }

    /** Binding cannot know the map key, so {@link ModelProperties} copies each profile with its key as the name. */
    ModelProfile named(String profileName) {
        return new ModelProfile(profileName, model, baseAddress, contextWindow, answerLimit, callTimeout,
                maxConcurrentCalls, retryLimit, temperature, apiKey, apiStyle, reasoning);
    }

    /** The key is left out on purpose: this text can end up in a log or an error message. */
    @Override
    public String toString() {
        return "ModelProfile[name=" + name + ", model=" + model + ", baseAddress=" + baseAddress
                + ", contextWindow=" + contextWindow + ", answerLimit=" + answerLimit
                + ", callTimeout=" + callTimeout + ", maxConcurrentCalls=" + maxConcurrentCalls
                + ", retryLimit=" + retryLimit + ", temperature=" + temperature + "]";
    }
}
