package io.github.bereket1144.agentcontroller.model;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** A valid model configuration and the context that reads it, shared by the tests of this package. */
final class ModelConfigFixture {

    static final String PRIMARY = "agent.models.primary";
    static final String FALLBACK = "agent.models.fallback";
    static final String SAFETY_MARGIN = "agent.models.safety-margin-percent";
    static final String LOCAL = "agent.models.profiles.local.";
    static final String HOSTED = "agent.models.profiles.hosted.";

    private ModelConfigFixture() {
    }

    /** One valid profile, named "local", which is also the primary. Tests change one value of it. */
    static Map<String, String> validConfiguration() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put(PRIMARY, "local");
        config.put(LOCAL + "model", "local-model");
        config.put(LOCAL + "base-address", "http://localhost:11434");
        config.put(LOCAL + "context-window", "32768");
        config.put(LOCAL + "answer-limit", "4096");
        config.put(LOCAL + "call-timeout", "300s");
        return config;
    }

    /** Adds a second valid profile, named "hosted", to the configuration. */
    static Map<String, String> withHosted(Map<String, String> config) {
        config.put(HOSTED + "model", "hosted-model");
        config.put(HOSTED + "base-address", "https://models.example.test");
        config.put(HOSTED + "context-window", "200000");
        config.put(HOSTED + "answer-limit", "16000");
        config.put(HOSTED + "call-timeout", "120s");
        return config;
    }

    /** A context holding only the model profiles, configured with exactly these properties. */
    static ApplicationContextRunner runnerWith(Map<String, String> config) {
        return new ApplicationContextRunner().withUserConfiguration(ModelProfiles.class)
                .withPropertyValues(config.entrySet().stream()
                        .map(property -> property.getKey() + "=" + property.getValue())
                        .toArray(String[]::new));
    }

    /** Sets an environment variable for the context, as the operating system would. */
    static ApplicationContextInitializer<ConfigurableApplicationContext> environmentVariable(
            String name, String value) {
        return context -> TestPropertyValues.of(name + "=" + value)
                .applyTo(context.getEnvironment(), TestPropertyValues.Type.SYSTEM_ENVIRONMENT);
    }
}
