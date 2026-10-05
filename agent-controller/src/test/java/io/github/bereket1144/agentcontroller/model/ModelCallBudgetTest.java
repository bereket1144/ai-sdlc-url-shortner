package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.model.ModelCallBudget.BudgetExhaustedException;
import io.github.bereket1144.agentcontroller.model.ModelCallBudget.Limit;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.Prompt;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.AttemptKind;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Outcome;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.StoredAttempt;
import io.github.bereket1144.agentcontroller.run.Run;
import io.github.bereket1144.agentcontroller.run.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ModelCallBudgetTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private final ModelCallRecords records = mock(ModelCallRecords.class);
    private final RunRepository runs = mock(RunRepository.class);
    private final ModelBudgetReservationRepository reservations = mock(ModelBudgetReservationRepository.class);

    @BeforeEach
    void allowReservation() {
        when(runs.lockById(RUN_ID)).thenReturn(Optional.of(mock(Run.class)));
        when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ModelCallBudget budget(int callsPerRun, int callsPerRole, long tokensPerRun) {
        return new ModelCallBudget(records, runs, reservations, callsPerRun, callsPerRole, tokensPerRun);
    }

    @Test
    @DisplayName("AC-AGT-8.1 configured limits must all be positive")
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> budget(0, 2, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget(2, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget(2, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-AGT-8.3 failed, repair, retry and fallback records all use the run call allowance")
    void everyStoredAttemptCounts() {
        when(records.forRun(RUN_ID)).thenReturn(List.of(
                attempt("requirements", AttemptKind.FIRST, Outcome.UNREACHABLE, 10, null),
                attempt("requirements", AttemptKind.RETRY, Outcome.SUCCESS, 10, 5L),
                attempt("design", AttemptKind.FALLBACK, Outcome.SUCCESS, 10, 5L),
                attempt("design", AttemptKind.REPAIR, Outcome.INVALID_ANSWER, 10, 5L)));

        assertThatThrownBy(() -> budget(4, 4, 1_000)
                .reserve(RUN_ID, "qa", 10, 10))
                .isInstanceOfSatisfying(BudgetExhaustedException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("BUDGET_EXHAUSTED");
                    assertThat(failure.limit()).isEqualTo(Limit.RUN_CALLS);
                });
    }

    @Test
    @DisplayName("AC-AGT-8.1 each role has its own configured call limit")
    void roleLimitDoesNotCountAnotherRole() {
        when(records.forRun(RUN_ID)).thenReturn(List.of(
                attempt("requirements", AttemptKind.FIRST, Outcome.SUCCESS, 10, 5L)));
        ModelCallBudget budget = budget(10, 1, 1_000);

        budget.reserve(RUN_ID, "design", 10, 10);
        assertThatThrownBy(() -> budget.reserve(RUN_ID, "requirements", 10, 10))
                .isInstanceOfSatisfying(BudgetExhaustedException.class, failure ->
                        assertThat(failure.limit()).isEqualTo(Limit.ROLE_CALLS));
    }

    @Test
    @DisplayName("AC-AGT-8.2 estimated tokens include earlier answers and room for the next full answer")
    void tokenLimitChecksTheNextAttemptBeforeItStarts() {
        when(records.forRun(RUN_ID)).thenReturn(List.of(
                attempt("requirements", AttemptKind.FIRST, Outcome.UNREACHABLE, 30, null),
                attempt("requirements", AttemptKind.RETRY, Outcome.SUCCESS, 20, 10L)));
        ModelCallBudget budget = budget(10, 10, 100);

        budget.reserve(RUN_ID, "design", 20, 20);
        assertThatThrownBy(() -> budget.reserve(RUN_ID, "design", 21, 20))
                .isInstanceOfSatisfying(BudgetExhaustedException.class, failure ->
                        assertThat(failure.limit()).isEqualTo(Limit.ESTIMATED_TOKENS));
    }

    @Test
    @DisplayName("AC-AGT-8.4 a new budget checker still counts this run's persisted records")
    void reconstructingTheCheckerDoesNotResetTheBudget() {
        when(records.forRun(RUN_ID)).thenReturn(List.of(), List.of(
                attempt("requirements", AttemptKind.FIRST, Outcome.SUCCESS, 10, 5L)));

        UUID reservationId = budget(1, 1, 100).reserve(RUN_ID, "requirements", 10, 10);
        budget(1, 1, 100).recorded(reservationId);
        verify(reservations).deleteById(reservationId);
        assertThatThrownBy(() -> budget(1, 1, 100)
                .reserve(RUN_ID, "requirements", 10, 10))
                .isInstanceOfSatisfying(BudgetExhaustedException.class, failure ->
                        assertThat(failure.limit()).isEqualTo(Limit.RUN_CALLS));
    }

    @Test
    @DisplayName("AC-AGT-8.2 a refused attempt never calls the model or creates a call record")
    void refusalStopsBeforeTheModelCall() {
        ModelProfile profile = new ModelProfile("local", "model", URI.create("http://localhost:11434"),
                1_000, 100, Duration.ofSeconds(2), 1, 2, 0, null);
        ModelClient model = mock(ModelClient.class);
        ModelProfiles profiles = mock(ModelProfiles.class);
        PromptBuilder prompts = mock(PromptBuilder.class);
        OutputValidator validator = mock(OutputValidator.class);
        when(profiles.forRole("requirements")).thenReturn(profile);
        when(prompts.build("requirements", profile, "task", List.of()))
                .thenReturn(new Prompt("system", "user", 10, 900, List.of()));
        when(records.forRun(RUN_ID)).thenReturn(List.of(
                attempt("requirements", AttemptKind.FIRST, Outcome.UNREACHABLE, 10, null)));
        StructuredAnswerService service = new StructuredAnswerService(model, profiles, prompts, validator,
                records, budget(1, 1, 1_000), 2, Duration.ZERO);

        assertThatThrownBy(() -> service.call(RUN_ID, "requirements", "task", List.of()))
                .isInstanceOf(BudgetExhaustedException.class);
        verifyNoInteractions(model, validator);
        verify(records, never()).record(any());
    }

    @Test
    @DisplayName("AC-AGT-8.3 an in-flight attempt consumes its run call allowance before a record exists")
    void outstandingReservationCounts() {
        when(reservations.findByRunId(RUN_ID)).thenReturn(List.of(
                new ModelBudgetReservation(RUN_ID, "requirements", 10, 20)));

        assertThatThrownBy(() -> budget(1, 1, 100).reserve(RUN_ID, "design", 10, 20))
                .isInstanceOfSatisfying(BudgetExhaustedException.class, failure ->
                        assertThat(failure.limit()).isEqualTo(Limit.RUN_CALLS));
    }

    @Test
    @DisplayName("AC-AGT-8.2 in-flight reservations also consume role calls and estimated tokens")
    void outstandingReservationChargesItsRoleAndTokens() {
        when(reservations.findByRunId(RUN_ID)).thenReturn(List.of(
                new ModelBudgetReservation(RUN_ID, "requirements", 30, 50)));

        assertThatThrownBy(() -> budget(10, 1, 1_000)
                .reserve(RUN_ID, "requirements", 10, 10))
                .isInstanceOfSatisfying(BudgetExhaustedException.class, failure ->
                        assertThat(failure.limit()).isEqualTo(Limit.ROLE_CALLS));
        budget(10, 10, 100).reserve(RUN_ID, "design", 10, 10);
        assertThatThrownBy(() -> budget(10, 10, 100)
                .reserve(RUN_ID, "design", 10, 11))
                .isInstanceOfSatisfying(BudgetExhaustedException.class, failure ->
                        assertThat(failure.limit()).isEqualTo(Limit.ESTIMATED_TOKENS));
    }

    @Test
    @DisplayName("AC-AGT-8.3 a call whose record fails keeps its reservation charged")
    void recordingFailureDoesNotReleaseReservation() {
        ModelProfile profile = new ModelProfile("local", "model", URI.create("http://localhost:11434"),
                1_000, 100, Duration.ofSeconds(2), 1, 2, 0, null);
        ModelClient model = mock(ModelClient.class);
        ModelProfiles profiles = mock(ModelProfiles.class);
        PromptBuilder prompts = mock(PromptBuilder.class);
        OutputValidator validator = mock(OutputValidator.class);
        ModelCallBudget budget = mock(ModelCallBudget.class);
        UUID reservationId = UUID.randomUUID();
        when(profiles.forRole("requirements")).thenReturn(profile);
        when(prompts.build("requirements", profile, "task", List.of()))
                .thenReturn(new Prompt("system", "user", 10, 900, List.of()));
        when(budget.reserve(RUN_ID, "requirements", 10, 100)).thenReturn(reservationId);
        when(model.call("requirements", profile, "system", "user"))
                .thenReturn(new ModelResult("{}", FinishReason.COMPLETE, null, null, Duration.ZERO));
        when(validator.validate("requirements", "{}"))
                .thenReturn(new OutputValidator.Validation(null, List.of()));
        doThrow(new IllegalStateException("call record could not be saved")).when(records).record(any());
        StructuredAnswerService service = new StructuredAnswerService(model, profiles, prompts, validator,
                records, budget, 2, Duration.ZERO);

        assertThatThrownBy(() -> service.call(RUN_ID, "requirements", "task", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("call record could not be saved");
        verify(budget, never()).recorded(reservationId);
    }

    @Test
    @DisplayName("AC-AGT-8.4 a call record in an open transaction keeps its reservation until commit")
    void reservationIsReleasedOnlyAfterCommit() {
        ModelProfile profile = new ModelProfile("local", "model", URI.create("http://localhost:11434"),
                1_000, 100, Duration.ofSeconds(2), 1, 2, 0, null);
        ModelClient model = mock(ModelClient.class);
        ModelProfiles profiles = mock(ModelProfiles.class);
        PromptBuilder prompts = mock(PromptBuilder.class);
        OutputValidator validator = mock(OutputValidator.class);
        ModelCallBudget budget = mock(ModelCallBudget.class);
        UUID reservationId = UUID.randomUUID();
        when(profiles.forRole("requirements")).thenReturn(profile);
        when(prompts.build("requirements", profile, "task", List.of()))
                .thenReturn(new Prompt("system", "user", 10, 900, List.of()));
        when(budget.reserve(RUN_ID, "requirements", 10, 100)).thenReturn(reservationId);
        when(model.call("requirements", profile, "system", "user"))
                .thenReturn(new ModelResult("{}", FinishReason.COMPLETE, null, null, Duration.ZERO));
        when(validator.validate("requirements", "{}"))
                .thenReturn(new OutputValidator.Validation(null, List.of()));
        StructuredAnswerService service = new StructuredAnswerService(model, profiles, prompts, validator,
                records, budget, 2, Duration.ZERO);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.call(RUN_ID, "requirements", "task", List.of());
            verify(budget, never()).recorded(reservationId);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(budget).recorded(reservationId);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private static StoredAttempt attempt(String role, AttemptKind kind, Outcome outcome,
                                         long promptTokens, Long answerTokens) {
        return new StoredAttempt(1, RUN_ID, role, "v1", "local", "model", kind, "fingerprint",
                "system", "user", null, null, promptTokens, answerTokens, null, null, "", 0, outcome);
    }
}
