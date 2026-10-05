package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class StructuredAnswerServiceTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final String VALID = """
            {"stories":[{"id":"S-1"}],"openQuestions":[],"assumptions":[]}
            """;
    private static final String INVALID = """
            {"stories":"wrong","openQuestions":[],"assumptions":[]}
            """;

    private final ModelProfile profile = new ModelProfile("local", "model",
            URI.create("http://localhost:11434"), 8192, 512, Duration.ofSeconds(2), 1, 2, 0, null);
    private final RoleCatalog roles;

    StructuredAnswerServiceTest() throws IOException {
        roles = new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties"));
    }

    @Test
    @DisplayName("AC-AGT-5.1 a valid first answer is returned without a repair call")
    void acceptsFirstValidAnswer() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addResult(answer(VALID, FinishReason.COMPLETE));

        StructuredAnswerService.AcceptedAnswer accepted = service(model, 2).call(
                RUN_ID, "requirements", "Write stories", inputs());

        assertThat(accepted.repairAttempts()).isZero();
        assertThat(accepted.value().get("stories").get(0).get("id").asString()).isEqualTo("S-1");
        assertThat(model.calls()).hasSize(1);
    }

    @Test
    @DisplayName("AC-AGT-5.4 an invalid answer is repaired with the previous text and exact check message")
    void repairsInvalidAnswer() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addResult(answer(INVALID, FinishReason.COMPLETE));
        model.addResult(answer(VALID, FinishReason.COMPLETE));

        StructuredAnswerService.AcceptedAnswer accepted = service(model, 2).call(
                RUN_ID, "requirements", "Write stories", inputs());

        assertThat(accepted.repairAttempts()).isEqualTo(1);
        assertThat(model.calls()).hasSize(2);
        assertThat(model.calls().get(1).userText()).contains(
                "[PREVIOUS_ANSWER]\n" + INVALID.strip(),
                "[VALIDATION_MESSAGES]\nWrong type for field stories: expected ARRAY\n[/VALIDATION_MESSAGES]");
        assertThat(model.calls().get(1).systemText()).isEqualTo(model.calls().get(0).systemText());
    }

    @Test
    @DisplayName("AC-AGT-5.4 a cut-off answer is repaired even when its JSON looks valid")
    void repairsCutOffAnswer() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addResult(answer(VALID, FinishReason.ANSWER_LIMIT));
        model.addResult(answer(VALID, FinishReason.COMPLETE));

        StructuredAnswerService.AcceptedAnswer accepted = service(model, 2).call(
                RUN_ID, "requirements", "Write stories", inputs());

        assertThat(accepted.repairAttempts()).isEqualTo(1);
        assertThat(model.calls().get(1).userText()).contains(
                "[PREVIOUS_ANSWER]\n" + VALID.strip(),
                "Answer was cut off at answer limit");
    }

    @Test
    @DisplayName("AC-AGT-5.5 two failed repairs produce OUTPUT_INVALID with only the last messages")
    void failsAfterRepairLimit() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addResult(answer(INVALID, FinishReason.COMPLETE));
        model.addResult(answer("""
                {"stories":false,"openQuestions":[],"assumptions":[]}
                """, FinishReason.COMPLETE));
        model.addResult(answer("{}", FinishReason.COMPLETE));

        assertThatThrownBy(() -> service(model, 2).call(RUN_ID, "requirements", "Write stories", inputs()))
                .isInstanceOfSatisfying(StructuredAnswerService.OutputInvalidException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("OUTPUT_INVALID");
                    assertThat(failure.repairAttempts()).isEqualTo(2);
                    assertThat(failure.messages()).containsExactly(
                            "Missing required field: stories",
                            "Missing required field: openQuestions",
                            "Missing required field: assumptions");
                    assertThat(failure.getMessage()).doesNotContain(INVALID);
                });
        assertThat(model.calls()).hasSize(3);
        assertThat(model.calls().get(2).userText()).contains(
                "[PREVIOUS_ANSWER]\n{\"stories\":false",
                "[VALIDATION_MESSAGES]\nWrong type for field stories: expected ARRAY")
                .doesNotContain("[PREVIOUS_ANSWER]\n" + INVALID.strip());
    }

    @Test
    @DisplayName("AC-AGT-5.5 a zero repair limit fails after the initial invalid answer")
    void zeroRepairsMeansOneCall() {
        ScriptedModelClient model = new ScriptedModelClient();
        model.addResult(answer(INVALID, FinishReason.COMPLETE));

        assertThatThrownBy(() -> service(model, 0).call(RUN_ID, "requirements", "Write stories", inputs()))
                .isInstanceOf(StructuredAnswerService.OutputInvalidException.class);
        assertThat(model.calls()).hasSize(1);
    }

    @Test
    @DisplayName("AC-AGT-5.6 a model infrastructure failure does not become an output repair")
    void keepsModelFailuresSeparate() {
        ScriptedModelClient model = new ScriptedModelClient();
        ModelCallException outage = new ModelCallException(FailureReason.UNREACHABLE, profile.name());
        model.addFailure(outage);
        model.addFailure(outage);
        model.addFailure(outage);

        assertThatThrownBy(() -> service(model, 2).call(RUN_ID, "requirements", "Write stories", inputs()))
                .isInstanceOfSatisfying(StructuredAnswerService.ModelUnavailableException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MODEL_UNAVAILABLE");
                    assertThat(failure.lastReason()).isEqualTo(FailureReason.UNREACHABLE);
                });
        assertThat(model.calls()).hasSize(3);
    }

    @Test
    @DisplayName("AC-AGT-5.4 a negative repair limit is refused at startup")
    void refusesNegativeRepairLimit() {
        assertThatThrownBy(() -> service(new ScriptedModelClient(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("agent.output.repair-limit must not be negative");
    }

    private StructuredAnswerService service(ScriptedModelClient model, int repairs) {
        ModelProfiles profiles = new ModelProfiles(new ModelProperties(Map.of("local", profile),
                "local", null, Map.of(), 10));
        PromptBuilder prompts = new PromptBuilder(profiles, roles, 3);
        OutputValidator validator = new OutputValidator(roles, List.of(new RequirementsStoryIdsCheck()));
        return new StructuredAnswerService(model, profiles, prompts, validator,
                mock(ModelCallRecords.class), mock(ModelCallBudget.class), repairs, Duration.ZERO);
    }

    private static List<PromptBuilder.PromptInput> inputs() {
        return List.of(new PromptBuilder.PromptInput(InputKind.ORIGINAL_REQUIREMENT,
                "v1", "Build a task tracker", null, false));
    }

    private static ModelResult answer(String text, FinishReason reason) {
        return new ModelResult(text, reason, null, null, Duration.ZERO);
    }
}
