package io.github.bereket1144.agentcontroller.modelcall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.model.ModelClient;
import io.github.bereket1144.agentcontroller.model.ModelClient.FailureReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelCallException;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.model.ModelProfiles;
import io.github.bereket1144.agentcontroller.model.OllamaModelClient;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Outcome;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.StoredAttempt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ReplayModelClientTest {

    private static final ModelProfile PROFILE = new ModelProfile("local", "model",
            URI.create("http://localhost:11434"), 32768, 4096, Duration.ofSeconds(300), 1, 2, 0.0, null);
    private static final String FINGERPRINT = InputFingerprint.of("local", "system 1", "user 1");

    @TempDir
    Path directory;

    @Test
    @DisplayName("AC-AGT-10.2 the recorded answer is served for the same role and input, and no other call gets it")
    void servesTheRecordedAnswerForTheSameRoleAndInput() {
        ModelClient replay = replayOf(StoredAttempts.answered("requirements", FINGERPRINT, Outcome.SUCCESS,
                "recorded answer", FinishReason.ANSWER_LIMIT));

        assertThatThrownBy(() -> replay.call("design", PROFILE, "system 1", "user 1"))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(FailureReason.REPLAY_MISSING));
        ModelResult result = replay.call("requirements", PROFILE, "system 1", "user 1");

        assertThat(result).isEqualTo(new ModelResult("recorded answer", FinishReason.ANSWER_LIMIT, 90L, 18L,
                Duration.ofMillis(1500)));
    }

    @Test
    @DisplayName("AC-AGT-10.4 a call with no recording, or whose recording is used up, fails with REPLAY_MISSING")
    void aCallWithNoRecordingFailsWithReplayMissing() {
        ModelClient replay = replayOf(StoredAttempts.answered("requirements", FINGERPRINT, Outcome.SUCCESS,
                "recorded answer", FinishReason.COMPLETE));

        assertThatThrownBy(() -> replay.call("requirements", PROFILE, "system 1", "another user text"))
                .isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(FailureReason.REPLAY_MISSING);
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).doesNotContain("another user text");
                });
        replay.call("requirements", PROFILE, "system 1", "user 1");
        assertThatThrownBy(() -> replay.call("requirements", PROFILE, "system 1", "user 1"))
                .isInstanceOfSatisfying(ModelCallException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(FailureReason.REPLAY_MISSING));
    }

    @Test
    @DisplayName("AC-AGT-10.5 a recorded failure is replayed as the same failure, then the retry gets its own record")
    void aRecordedFailureReplaysBeforeTheRetry() {
        ModelClient replay = replayOf(
                StoredAttempts.failed("requirements", FINGERPRINT, Outcome.RATE_LIMITED),
                StoredAttempts.answered("requirements", FINGERPRINT, Outcome.SUCCESS, "second try",
                        FinishReason.COMPLETE));

        assertThatThrownBy(() -> replay.call("requirements", PROFILE, "system 1", "user 1"))
                .isInstanceOfSatisfying(ModelCallException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(FailureReason.RATE_LIMITED);
                    assertThat(failure.retryable()).isTrue();
                });
        assertThat(replay.call("requirements", PROFILE, "system 1", "user 1").text()).isEqualTo("second try");
    }

    @Test
    @DisplayName("AC-AGT-10.6 the configured mode picks the live or the replay client, and replay needs a recording")
    void theModeAndTheRecordingComeFromConfiguration() {
        Path file = directory.resolve("run.json");
        Recording.write(file, List.of());
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(MockedCollaborators.class, OllamaModelClient.class, ReplayModelClient.class);

        runner.run(context -> assertThat(context.getBeansOfType(ModelClient.class).values())
                .singleElement().isInstanceOf(OllamaModelClient.class));
        runner.withPropertyValues("agent.model-client.mode=live")
                .run(context -> assertThat(context.getBean(ModelClient.class)).isInstanceOf(OllamaModelClient.class));
        runner.withPropertyValues("agent.model-client.mode=replay", "agent.model-client.recording=" + file)
                .run(context -> assertThat(context.getBeansOfType(ModelClient.class).values())
                        .singleElement().isInstanceOf(ReplayModelClient.class));
        runner.withPropertyValues("agent.model-client.mode=replay")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("agent.model-client.recording"));
    }

    /** The mocks come from a configuration class, so ModelProfiles' own property binding is not set up. */
    @Configuration
    static class MockedCollaborators {

        @Bean
        ModelProfiles profiles() {
            return mock(ModelProfiles.class);
        }

        @Bean
        RoleCatalog roles() {
            return mock(RoleCatalog.class);
        }
    }

    private ReplayModelClient replayOf(StoredAttempt... attempts) {
        Path file = directory.resolve("recording.json");
        Recording.write(file, List.of(attempts));
        return new ReplayModelClient(file.toString());
    }
}
