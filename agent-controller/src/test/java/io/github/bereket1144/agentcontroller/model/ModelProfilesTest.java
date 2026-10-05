package io.github.bereket1144.agentcontroller.model;

import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.FALLBACK;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.LOCAL;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.PRIMARY;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.environmentVariable;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.runnerWith;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.validConfiguration;
import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.withHosted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

// No Docker and no database: a Spring context that holds only the model profiles.
class ModelProfilesTest {

    private static final String KEY = "key-ZX9917-must-not-leak";
    private static final String HOSTED_KEY_VARIABLE = "AGENT_MODELS_PROFILES_HOSTED_APIKEY";

    @Test
    @DisplayName("AC-AGT-2.1 a profile is named by its key and holds every configured value")
    void profileHoldsEveryConfiguredValue() {
        Map<String, String> config = validConfiguration();
        config.put(LOCAL + "call-timeout", "90s");
        config.put(LOCAL + "max-concurrent-calls", "3");
        config.put(LOCAL + "retry-limit", "5");
        config.put(LOCAL + "temperature", "0.7");

        runnerWith(config).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ModelProfiles.class).primary())
                    .returns("local", ModelProfile::name)
                    .returns("local-model", ModelProfile::model)
                    .returns(URI.create("http://localhost:11434"), ModelProfile::baseAddress)
                    .returns(32768, ModelProfile::contextWindow)
                    .returns(4096, ModelProfile::answerLimit)
                    .returns(Duration.ofSeconds(90), ModelProfile::callTimeout)
                    .returns(3, ModelProfile::maxConcurrentCalls)
                    .returns(5, ModelProfile::retryLimit)
                    .returns(0.7, ModelProfile::temperature);
        });
    }

    @Test
    @DisplayName("AC-AGT-2.1 a profile that leaves them out gets one call at a time, two retries, temperature 0 and no key")
    void omittedOptionsTakeTheirDefaults() {
        runnerWith(validConfiguration()).run(context ->
                assertThat(context.getBean(ModelProfiles.class).primary())
                        .returns(1, ModelProfile::maxConcurrentCalls)
                        .returns(2, ModelProfile::retryLimit)
                        .returns(0.0, ModelProfile::temperature)
                        .returns(null, ModelProfile::apiKey));
    }

    @Test
    @DisplayName("AC-AGT-2.4 the safety margin is 10 percent of the window by default, and an answer limit one token under it is accepted")
    void answerLimitJustInsideTheDefaultMarginIsAccepted() {
        Map<String, String> config = validConfiguration();
        config.put(LOCAL + "context-window", "1000");
        config.put(LOCAL + "answer-limit", "899"); // 899 + a margin of 100 = 999, below the window of 1000

        runnerWith(config).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ModelProperties.class).safetyMarginPercent()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("AC-AGT-2.3 the profile named as fallback is the fallback, and the primary stays the primary")
    void configuredFallbackIsReturned() {
        Map<String, String> config = withHosted(validConfiguration());
        config.put(FALLBACK, "hosted");

        runnerWith(config).run(context -> {
            ModelProfiles profiles = context.getBean(ModelProfiles.class);
            assertThat(profiles.primary().name()).isEqualTo("local");
            assertThat(profiles.fallback().map(ModelProfile::name)).hasValue("hosted");
        });
    }

    @Test
    @DisplayName("AC-AGT-2.3 an empty fallback setting means there is no fallback")
    void emptyFallbackMeansNone() {
        Map<String, String> config = validConfiguration();
        config.put(FALLBACK, "");

        runnerWith(config).run(context ->
                assertThat(context.getBean(ModelProfiles.class).fallback()).isEmpty());
    }

    @Test
    @DisplayName("AC-AGT-2.5 a role gets the profile assigned to it, and every other role gets the primary")
    void roleGetsItsAssignedProfileOrThePrimary() {
        Map<String, String> config = withHosted(validConfiguration());
        config.put("agent.models.roles.design", "hosted");

        runnerWith(config).run(context -> {
            ModelProfiles profiles = context.getBean(ModelProfiles.class);
            assertThat(profiles.forRole("design").name()).isEqualTo("hosted");
            assertThat(profiles.forRole("qa").name()).isEqualTo("local");
        });
    }

    @Test
    @DisplayName("AC-AGT-2.5 a role assigned through an environment variable is found by its camel-case name")
    void roleAssignedThroughTheEnvironmentIsFoundByItsCamelCaseName() {
        runnerWith(withHosted(validConfiguration()))
                .withInitializer(environmentVariable("AGENT_MODELS_ROLES_IMPACTANALYSIS", "hosted"))
                .run(context -> assertThat(context.getBean(ModelProfiles.class).forRole("impactAnalysis").name())
                        .isEqualTo("hosted"));
    }

    @Test
    @DisplayName("AC-AGT-2.5 asking for the profile of no role is refused")
    void nullRoleIsRefused() {
        runnerWith(validConfiguration()).run(context -> assertThatNullPointerException()
                .isThrownBy(() -> context.getBean(ModelProfiles.class).forRole(null))
                .withMessage("role must not be null"));
    }

    @Test
    @DisplayName("AC-AGT-2.2 a key supplied through the environment is bound and never printed")
    void keyFromTheEnvironmentIsBoundButNeverPrinted() {
        Map<String, String> config = withHosted(validConfiguration());
        config.put(PRIMARY, "hosted");

        runnerWith(config).withInitializer(environmentVariable(HOSTED_KEY_VARIABLE, KEY)).run(context -> {
            ModelProfile hosted = context.getBean(ModelProfiles.class).primary();
            assertThat(hosted.apiKey()).isEqualTo(KEY);
            assertThat(hosted.toString()).doesNotContain(KEY);
            assertThat(context.getBean(ModelProperties.class).toString()).doesNotContain(KEY);
        });
    }

    @Test
    @DisplayName("AC-AGT-2.2 startup logs one line with the profiles, the primary and the fallback, and never a key")
    void startupLogsTheProfilesButNeverAKey() {
        Map<String, String> config = withHosted(validConfiguration());
        config.put(FALLBACK, "hosted");

        List<String> messages = capturedLogs(() -> runnerWith(config)
                .withInitializer(environmentVariable(HOSTED_KEY_VARIABLE, KEY))
                .run(context -> assertThat(context).hasNotFailed()));

        assertThat(messages).singleElement().asString()
                .contains("event=model_profiles_loaded", "primary=local", "fallback=hosted",
                        "hosted(model=hosted-model,window=200000)", "local(model=local-model,window=32768)")
                .doesNotContain(KEY);
    }

    private static List<String> capturedLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ModelProfiles.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
