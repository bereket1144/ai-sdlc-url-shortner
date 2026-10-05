package io.github.bereket1144.agentcontroller.model;

import java.net.URI;
import java.time.Duration;

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
        String apiKey) {

    /** Binding cannot know the map key, so {@link ModelProperties} copies each profile with its key as the name. */
    ModelProfile named(String profileName) {
        return new ModelProfile(profileName, model, baseAddress, contextWindow, answerLimit, callTimeout,
                maxConcurrentCalls, retryLimit, temperature, apiKey);
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
