package io.github.bereket1144.agentcontroller.model;

import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.FALLBACK;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.HOSTED;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.LOCAL;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.PRIMARY;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.SAFETY_MARGIN;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.runnerWith;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.validConfiguration;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.withHosted;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// No Docker and no database: each row starts a context that must fail and says what the message must name.
class ModelConfigurationFailureTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    @DisplayName("AC-AGT-2.4 invalid configuration stops startup with a message that names the profile and the field")
    void invalidConfigurationStopsStartup(String description, Consumer<Map<String, String>> breakIt,
            List<String> messageNames) {
        Map<String, String> config = withHosted(validConfiguration());
        breakIt.accept(config);

        runnerWith(config).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContainingAll(messageNames.toArray(String[]::new));
        });
    }

    static Stream<Arguments> invalidConfigurations() {
        return Stream.of(
                invalid("a missing context window", config -> config.remove(LOCAL + "context-window"),
                        "agent.models.profiles.local.context-window"),
                invalid("a context window of -1", config -> config.put(LOCAL + "context-window", "-1"),
                        "agent.models.profiles.local.context-window"),
                invalid("a missing answer limit", config -> config.remove(LOCAL + "answer-limit"),
                        "agent.models.profiles.local.answer-limit"),
                invalid("an answer limit of 0", config -> config.put(LOCAL + "answer-limit", "0"),
                        "agent.models.profiles.local.answer-limit"),
                invalid("a missing call timeout", config -> config.remove(LOCAL + "call-timeout"),
                        "agent.models.profiles.local.call-timeout"),
                invalid("a call timeout of 0 seconds", config -> config.put(LOCAL + "call-timeout", "0s"),
                        "agent.models.profiles.local.call-timeout"),
                invalid("a call limit of 0", config -> config.put(LOCAL + "max-concurrent-calls", "0"),
                        "agent.models.profiles.local.max-concurrent-calls"),
                invalid("an answer limit that leaves no room for a prompt", config -> {
                    config.put(LOCAL + "context-window", "4096");
                    config.put(LOCAL + "answer-limit", "4000");
                }, "agent.models.profiles.local.answer-limit", "context-window"),
                invalid("an answer limit and margin that together equal the window", config -> {
                    config.put(LOCAL + "context-window", "1000");
                    config.put(LOCAL + "answer-limit", "900"); // 900 + 10 percent of 1000 is not smaller than 1000
                }, "agent.models.profiles.local.answer-limit", "context-window"),
                invalid("an answer limit too large for a configured margin of 50 percent", config -> {
                    config.put(SAFETY_MARGIN, "50");
                    config.put(LOCAL + "context-window", "1000");
                    config.put(LOCAL + "answer-limit", "600");
                }, "agent.models.profiles.local.answer-limit", "50%"),
                invalid("an invalid second profile is named, not the first", config ->
                        config.put(HOSTED + "context-window", "0"), "agent.models.profiles.hosted.context-window"),
                invalid("a missing model identifier", config -> config.remove(LOCAL + "model"),
                        "agent.models.profiles.local.model"),
                invalid("a missing base address", config -> config.remove(LOCAL + "base-address"),
                        "agent.models.profiles.local.base-address"),
                invalid("a base address without a scheme and host", config ->
                        config.put(LOCAL + "base-address", "localhost:11434"),
                        "agent.models.profiles.local.base-address", "http or https"),
                invalid("the openai style without a key", config -> config.put(LOCAL + "api-style", "openai"),
                        "agent.models.profiles.local.api-key", "openai"),
                invalid("the ollama style with a key", config -> {
                    config.put(LOCAL + "api-style", "ollama");
                    config.put(LOCAL + "api-key", "a-key");
                }, "agent.models.profiles.local.api-key", "ollama"),
                invalid("a key that is set but empty", config -> config.put(HOSTED + "api-key", ""),
                        "agent.models.profiles.hosted.api-key", "empty"),
                invalid("a key that would be sent over plain http", config -> {
                    config.put(HOSTED + "api-key", "a-key");
                    config.put(HOSTED + "base-address", "http://models.example.test");
                }, "agent.models.profiles.hosted.base-address", "https"),
                invalid("a retry limit of -1", config -> config.put(LOCAL + "retry-limit", "-1"),
                        "agent.models.profiles.local.retry-limit"),
                invalid("a safety margin of -1 percent", config -> config.put(SAFETY_MARGIN, "-1"),
                        "agent.models.safety-margin-percent"),
                invalid("a safety margin of 100 percent", config -> config.put(SAFETY_MARGIN, "100"),
                        "agent.models.safety-margin-percent"),
                invalid("no primary", config -> config.remove(PRIMARY), "agent.models.primary"),
                invalid("a primary that is not a profile", config -> config.put(PRIMARY, "nowhere"),
                        "agent.models.primary", "'nowhere'"),
                invalid("a fallback that is not a profile", config -> config.put(FALLBACK, "nowhere"),
                        "agent.models.fallback", "'nowhere'"),
                invalid("a fallback equal to the primary", config -> config.put(FALLBACK, "local"),
                        "agent.models.fallback", "agent.models.primary", "'local'"),
                invalid("a role assigned to a profile that does not exist",
                        config -> config.put("agent.models.roles.design", "nowhere"),
                        "agent.models.roles.design", "'nowhere'"));
    }

    private static Arguments invalid(String description, Consumer<Map<String, String>> breakIt,
            String... messageNames) {
        return Arguments.of(description, breakIt, List.of(messageNames));
    }
}
