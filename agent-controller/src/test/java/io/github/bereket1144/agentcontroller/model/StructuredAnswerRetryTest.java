package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.model.ModelClient.FailureReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelCallException;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.ModelUnavailableException;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Attempt;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.AttemptKind;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class StructuredAnswerRetryTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final String REQUIREMENTS_ANSWER = """
            {"stories":[{"id":"S-1"}],"openQuestions":[],"assumptions":[]}
            """;
    private static final String DESIGN_ANSWER = """
            {"components":[],"interfaces":[],"dataFlow":"flow","decisions":[],
             "alternatives":[],"risks":[],"diagrams":[]}
            """;

    private final ModelProfile primary = new ModelProfile("local", "local-model",
            URI.create("http://localhost:11434"), 8192, 512, Duration.ofSeconds(2), 1, 2, 0, null);
    private final ModelProfile fallback = new ModelProfile("hosted", "hosted-model",
            URI.create("https://models.example.test"), 2000, 200, Duration.ofSeconds(2), 1, 2, 0, null);
    private final ModelCallRecords records = mock(ModelCallRecords.class);
    private final RoleCatalog roles;

    StructuredAnswerRetryTest() throws IOException {
        roles = new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties"));
    }

    @Test
    @DisplayName("AC-AGT-6.1 retryable calls keep the same profile and exact prompt until one succeeds")
    void retriesSameCall() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addFailure(failure(FailureReason.UNREACHABLE, primary));
        model.addFailure(failure(FailureReason.TIMED_OUT, primary));
        model.addResult(answer(REQUIREMENTS_ANSWER, FinishReason.COMPLETE));

        StructuredAnswerService.AcceptedAnswer accepted = service(model, profiles(true), Duration.ZERO)
                .call(RUN_ID, "requirements", "Write stories", requirementInput());

        assertThat(accepted.repairAttempts()).isZero();
        assertThat(model.calls()).hasSize(3)
                .allSatisfy(call -> assertThat(call.profile().name()).isEqualTo("local"));
        assertThat(model.calls().stream().map(ScriptedModelClient.Call::systemText).distinct().toList()).hasSize(1);
        assertThat(model.calls().stream().map(ScriptedModelClient.Call::userText).distinct().toList()).hasSize(1);
        List<Attempt> recorded = attempts(3);
        assertThat(recorded).extracting(Attempt::kind)
                .containsExactly(AttemptKind.FIRST, AttemptKind.RETRY, AttemptKind.RETRY);
        assertThat(recorded).allSatisfy(attempt -> {
            assertThat(attempt.runId()).isEqualTo(RUN_ID);
            assertThat(attempt.estimatedPromptTokens()).isPositive();
        });
        assertThat(recorded).extracting(Attempt::outcome)
                .containsExactly(Outcome.UNREACHABLE, Outcome.TIMED_OUT, Outcome.SUCCESS);
    }

    @Test
    @DisplayName("AC-AGT-6.1 a non-retryable call failure makes no retry or fallback call")
    void nonRetryableStopsImmediately() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addFailure(failure(FailureReason.REQUEST_REJECTED, primary));

        assertThatThrownBy(() -> service(model, profiles(true), Duration.ZERO)
                .call(RUN_ID, "requirements", "Write stories", requirementInput()))
                .isInstanceOfSatisfying(ModelUnavailableException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MODEL_UNAVAILABLE");
                    assertThat(failure.lastReason()).isEqualTo(FailureReason.REQUEST_REJECTED);
                });
        assertThat(model.calls()).hasSize(1);
        assertThat(attempts(1)).extracting(Attempt::outcome).containsExactly(Outcome.REQUEST_REJECTED);
    }

    @Test
    @DisplayName("AC-AGT-6.2 exhausted primary retries make one fallback call with a prompt sized again")
    void fallbackResizesPrompt() {
        ScriptedModelClient model = new ScriptedModelClient();
        failPrimaryThreeTimes(model);
        model.addResult(answer(DESIGN_ANSWER, FinishReason.COMPLETE));
        String full = "F".repeat(5_000);
        List<PromptInput> inputs = List.of(new PromptInput(InputKind.REQUIREMENTS_ARTIFACT,
                "v1", full, "short requirements", false));

        StructuredAnswerService.AcceptedAnswer accepted = service(model, profiles(true), Duration.ZERO)
                .call(RUN_ID, "design", "Design it", inputs);

        assertThat(accepted.value().get("dataFlow").asString()).isEqualTo("flow");
        assertThat(model.calls()).hasSize(4);
        assertThat(model.calls().get(0).userText()).contains(full);
        assertThat(model.calls().get(3).profile().name()).isEqualTo("hosted");
        assertThat(model.calls().get(3).userText()).contains("short requirements").doesNotContain(full);
        assertThat(attempts(4)).extracting(Attempt::kind)
                .containsExactly(AttemptKind.FIRST, AttemptKind.RETRY, AttemptKind.RETRY, AttemptKind.FALLBACK);
    }

    @Test
    @DisplayName("AC-AGT-6.3 an invalid answer repairs on its provider without spending infrastructure retries")
    void invalidAnswerDoesNotSwitchModels() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addResult(answer("{}", FinishReason.COMPLETE));
        model.addResult(answer(REQUIREMENTS_ANSWER, FinishReason.COMPLETE));

        StructuredAnswerService.AcceptedAnswer accepted = service(model, profiles(true), Duration.ZERO)
                .call(RUN_ID, "requirements", "Write stories", requirementInput());

        assertThat(accepted.repairAttempts()).isEqualTo(1);
        assertThat(model.calls()).extracting(call -> call.profile().name())
                .containsExactly("local", "local");
        assertThat(attempts(2)).extracting(Attempt::kind)
                .containsExactly(AttemptKind.FIRST, AttemptKind.REPAIR);
    }

    @Test
    @DisplayName("AC-AGT-6.3 an invalid fallback answer repairs on the fallback profile")
    void fallbackAnswerRepairsOnFallback() {
        ScriptedModelClient model = new ScriptedModelClient();
        failPrimaryThreeTimes(model);
        model.addResult(answer(REQUIREMENTS_ANSWER, FinishReason.ANSWER_LIMIT));
        model.addResult(answer(REQUIREMENTS_ANSWER, FinishReason.COMPLETE));

        StructuredAnswerService.AcceptedAnswer accepted = service(model, profiles(true), Duration.ZERO)
                .call(RUN_ID, "requirements", "Write stories", requirementInput());

        assertThat(accepted.repairAttempts()).isEqualTo(1);
        assertThat(model.calls()).extracting(call -> call.profile().name())
                .containsExactly("local", "local", "local", "hosted", "hosted");
        List<Attempt> recorded = attempts(5);
        assertThat(recorded).extracting(Attempt::kind)
                .containsExactly(AttemptKind.FIRST, AttemptKind.RETRY, AttemptKind.RETRY,
                        AttemptKind.FALLBACK, AttemptKind.REPAIR);
        assertThat(recorded).extracting(Attempt::outcome)
                .containsExactly(Outcome.UNREACHABLE, Outcome.UNREACHABLE, Outcome.UNREACHABLE,
                        Outcome.ANSWER_CUT_OFF, Outcome.SUCCESS);
    }

    @Test
    @DisplayName("AC-AGT-6.4 a failed fallback is tried once and reports its last reason")
    void fallbackFailureIsTerminal() {
        ScriptedModelClient model = new ScriptedModelClient();
        failPrimaryThreeTimes(model);
        model.addFailure(failure(FailureReason.RATE_LIMITED, fallback));

        assertThatThrownBy(() -> service(model, profiles(true), Duration.ZERO)
                .call(RUN_ID, "requirements", "Write stories", requirementInput()))
                .isInstanceOfSatisfying(ModelUnavailableException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MODEL_UNAVAILABLE");
                    assertThat(failure.lastReason()).isEqualTo(FailureReason.RATE_LIMITED);
                });
        assertThat(model.calls()).hasSize(4);
        assertThat(attempts(4)).extracting(Attempt::outcome)
                .containsExactly(Outcome.UNREACHABLE, Outcome.UNREACHABLE,
                        Outcome.UNREACHABLE, Outcome.RATE_LIMITED);
    }

    @Test
    @DisplayName("AC-AGT-6.4 exhausted retries without a fallback report MODEL_UNAVAILABLE")
    void noFallbackIsTerminal() {
        ScriptedModelClient model = new ScriptedModelClient();
        failPrimaryThreeTimes(model);

        assertThatThrownBy(() -> service(model, profiles(false), Duration.ZERO)
                .call(RUN_ID, "requirements", "Write stories", requirementInput()))
                .isInstanceOfSatisfying(ModelUnavailableException.class, failure ->
                        assertThat(failure.lastReason()).isEqualTo(FailureReason.UNREACHABLE));
        assertThat(model.calls()).hasSize(3);
        assertThat(attempts(3)).hasSize(3);
    }

    @Test
    @DisplayName("AC-AGT-6.2 a role assigned to another profile does not fall back to itself")
    void assignedProfileDoesNotFallBackToItself() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addFailure(failure(FailureReason.UNREACHABLE, fallback));
        model.addFailure(failure(FailureReason.UNREACHABLE, fallback));
        model.addFailure(failure(FailureReason.UNREACHABLE, fallback));
        ModelProfiles assigned = new ModelProfiles(new ModelProperties(Map.of("local", primary, "hosted", fallback),
                "local", "hosted", Map.of("requirements", "hosted"), 10));

        assertThatThrownBy(() -> service(model, assigned, Duration.ZERO)
                .call(RUN_ID, "requirements", "Write stories", requirementInput()))
                .isInstanceOf(ModelUnavailableException.class);
        assertThat(model.calls()).extracting(call -> call.profile().name())
                .containsExactly("hosted", "hosted", "hosted");
        assertThat(attempts(3)).extracting(Attempt::kind)
                .containsExactly(AttemptKind.FIRST, AttemptKind.RETRY, AttemptKind.RETRY);
    }

    @Test
    @DisplayName("AC-AGT-6.1 retry waits increase and are capped without sleeping in this test")
    void retryWaitsIncrease() {
        StructuredAnswerService service = service(new ScriptedModelClient(), profiles(true),
                Duration.ofMillis(250));

        assertThat(service.retryDelayMillis(1)).isEqualTo(250);
        assertThat(service.retryDelayMillis(2)).isEqualTo(500);
        assertThat(service.retryDelayMillis(3)).isEqualTo(1_000);
        assertThat(service.retryDelayMillis(20)).isEqualTo(30_000);
        assertThatThrownBy(() -> service.retryDelayMillis(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retryNumber must be positive");
    }

    @Test
    @DisplayName("AC-AGT-6.1 an interrupted retry stops and preserves the interrupt flag")
    void interruptedRetryStops() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addFailure(failure(FailureReason.UNREACHABLE, primary));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> service(model, profiles(true), Duration.ZERO)
                    .call(RUN_ID, "requirements", "Write stories", requirementInput()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Model retry interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(model.calls()).hasSize(1);
        assertThat(attempts(1)).hasSize(1);
    }

    @Test
    @DisplayName("AC-AGT-6.1 an invalid retry delay is rejected at startup")
    void rejectsInvalidRetryDelay() {
        assertThatThrownBy(() -> service(new ScriptedModelClient(), profiles(true), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agent.models.retry-delay must be from 0 to 30 seconds");
        assertThatThrownBy(() -> service(new ScriptedModelClient(), profiles(true),
                Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agent.models.retry-delay must be from 0 to 30 seconds");
        assertThatThrownBy(() -> service(new ScriptedModelClient(), profiles(true),
                Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agent.models.retry-delay must be from 0 to 30 seconds");
    }

    @Test
    @DisplayName("AC-AGT-6.5 a successful call without reported duration still gets a timed call record")
    void recordsElapsedDurationWhenProviderOmitsIt() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addResult(new ModelResult(REQUIREMENTS_ANSWER, FinishReason.COMPLETE, null, null, null));

        service(model, profiles(true), Duration.ZERO)
                .call(RUN_ID, "requirements", "Write stories", requirementInput());

        assertThat(attempts(1).getFirst().duration()).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);
    }

    private StructuredAnswerService service(ScriptedModelClient model, ModelProfiles profiles, Duration retryDelay) {
        PromptBuilder prompts = new PromptBuilder(profiles, roles, 3);
        OutputValidator validator = new OutputValidator(roles, List.of(new RequirementsStoryIdsCheck()));
        return new StructuredAnswerService(model, profiles, prompts, validator,
                records, mock(ModelCallBudget.class), 2, retryDelay);
    }

    private ModelProfiles profiles(boolean includeFallback) {
        return new ModelProfiles(new ModelProperties(Map.of("local", primary, "hosted", fallback),
                "local", includeFallback ? "hosted" : null, Map.of(), 10));
    }

    private List<Attempt> attempts(int count) {
        ArgumentCaptor<Attempt> captor = ArgumentCaptor.forClass(Attempt.class);
        verify(records, times(count)).record(captor.capture());
        return captor.getAllValues();
    }

    private void failPrimaryThreeTimes(ScriptedModelClient model) {
        model.addFailure(failure(FailureReason.UNREACHABLE, primary));
        model.addFailure(failure(FailureReason.UNREACHABLE, primary));
        model.addFailure(failure(FailureReason.UNREACHABLE, primary));
    }

    private static ModelCallException failure(FailureReason reason, ModelProfile profile) {
        return new ModelCallException(reason, profile.name());
    }

    private static ModelResult answer(String text, FinishReason reason) {
        return new ModelResult(text, reason, null, null, Duration.ZERO);
    }

    private static List<PromptInput> requirementInput() {
        return List.of(new PromptInput(InputKind.ORIGINAL_REQUIREMENT,
                "v1", "Build a task tracker", null, false));
    }
}
