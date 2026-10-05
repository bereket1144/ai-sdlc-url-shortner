package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.concurrent.CompletionException;

import io.github.bereket1144.agentcontroller.model.ModelCallBudget.BudgetExhaustedException;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.ContextTooLargeException;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.ModelUnavailableException;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.OutputInvalidException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunFailureReasonTest {

    @Test
    @DisplayName("AC-AGT-9.1 all failure codes have a fixed nonblank reviewer sentence")
    void allCodesHaveFixedMessages() {
        assertThat(Arrays.stream(RunFailureReason.values()).map(Enum::name)).containsExactly(
                "OUTPUT_INVALID", "MODEL_UNAVAILABLE", "CONTEXT_TOO_LARGE", "BUDGET_EXHAUSTED",
                "REPLAY_MISSING", "ACCESS_DENIED", "REJECTED", "ASSEMBLY_CONFLICT",
                "STOPPED_BY_REVIEWER", "STAGE_TIMEOUT", "RECOVERY_FAILED", "UNEXPECTED");
        assertThat(RunFailureReason.values()).allSatisfy(reason ->
                assertThat(reason.message()).isNotBlank().endsWith("."));
    }

    @Test
    @DisplayName("AC-AGT-9.1 known typed failures map to their fixed code through graph wrappers")
    void classifiesKnownFailures() {
        assertThat(RunFailureReason.from(new CompletionException(mock(OutputInvalidException.class))))
                .isEqualTo(RunFailureReason.OUTPUT_INVALID);
        assertThat(RunFailureReason.from(new CompletionException(mock(ModelUnavailableException.class))))
                .isEqualTo(RunFailureReason.MODEL_UNAVAILABLE);
        assertThat(RunFailureReason.from(new CompletionException(mock(ContextTooLargeException.class))))
                .isEqualTo(RunFailureReason.CONTEXT_TOO_LARGE);
        assertThat(RunFailureReason.from(new CompletionException(mock(BudgetExhaustedException.class))))
                .isEqualTo(RunFailureReason.BUDGET_EXHAUSTED);
    }

    @Test
    @DisplayName("AC-AGT-9.4 an untyped exception maps to UNEXPECTED without exposing its message")
    void unexpectedFailureUsesFixedText() {
        assertThat(RunFailureReason.from(new IllegalStateException("secret model answer")))
                .isEqualTo(RunFailureReason.UNEXPECTED);
        assertThat(RunFailureReason.UNEXPECTED.message()).doesNotContain("secret");
    }
}
