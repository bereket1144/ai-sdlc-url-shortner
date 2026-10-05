package io.github.bereket1144.agentcontroller.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import io.github.bereket1144.agentcontroller.model.ModelClient.FailureReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelCallException;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.Prompt;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Attempt;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.AttemptKind;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** Coordinates model calls, retries, fallback and output repair for one role answer. */
@Service
public class StructuredAnswerService {

    private static final Logger log = LoggerFactory.getLogger(StructuredAnswerService.class);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);

    private final ModelClient model;
    private final ModelProfiles profiles;
    private final PromptBuilder prompts;
    private final OutputValidator validator;
    private final ModelCallRecords records;
    private final ModelCallBudget budget;
    private final int repairLimit;
    private final Duration retryBaseDelay;

    public StructuredAnswerService(ModelClient model, ModelProfiles profiles, PromptBuilder prompts,
                                   OutputValidator validator, ModelCallRecords records, ModelCallBudget budget,
                                   @Value("${agent.output.repair-limit:2}") int repairLimit,
                                   @Value("${agent.models.retry-delay:200ms}") Duration retryBaseDelay) {
        if (repairLimit < 0) {
            throw new IllegalArgumentException("agent.output.repair-limit must not be negative");
        }
        if (retryBaseDelay == null || retryBaseDelay.isNegative()
                || retryBaseDelay.compareTo(MAX_RETRY_DELAY) > 0) {
            throw new IllegalArgumentException("agent.models.retry-delay must be from 0 to 30 seconds");
        }
        this.model = model;
        this.profiles = profiles;
        this.prompts = prompts;
        this.validator = validator;
        this.records = records;
        this.budget = budget;
        this.repairLimit = repairLimit;
        this.retryBaseDelay = retryBaseDelay;
    }

    public AcceptedAnswer call(UUID runId, String roleId, String task, List<PromptInput> inputs) {
        Objects.requireNonNull(runId, "runId must not be null");
        ModelProfile profile = profiles.forRole(roleId);
        int repairs = 0;
        String previousAnswer = null;
        List<String> messages = null;
        while (true) {
            String lastAnswer = previousAnswer;
            List<String> lastMessages = messages;
            Function<ModelProfile, Prompt> promptFor = candidate -> {
                Prompt original = prompts.build(roleId, candidate, task, inputs);
                return lastAnswer == null ? original
                        : prompts.repair(roleId, original, lastAnswer, lastMessages);
            };
            Invocation invocation = callWithRecovery(runId, roleId, profile, promptFor,
                    repairs == 0 ? AttemptKind.FIRST : AttemptKind.REPAIR);
            profile = invocation.profile();
            ModelResult answer = invocation.result();
            OutputValidator.Validation checked = answer.finishReason() == FinishReason.ANSWER_LIMIT
                    ? new OutputValidator.Validation(null, List.of("Answer was cut off at answer limit"))
                    : validator.validate(roleId, answer.text());
            Outcome outcome = answer.finishReason() == FinishReason.ANSWER_LIMIT ? Outcome.ANSWER_CUT_OFF
                    : checked.valid() ? Outcome.SUCCESS : Outcome.INVALID_ANSWER;
            records.record(new Attempt(runId, roleId, profile, invocation.kind(),
                    invocation.prompt().systemText(), invocation.prompt().userText(), answer, outcome,
                    invocation.prompt().estimatedTokens(), prompts.estimateAnswerTokens(answer.text()),
                    invocation.prompt().reductions(), invocation.duration()));
            settleReservation(invocation.reservationId());
            if (checked.valid()) {
                log.info("event=model_output_accepted runId={} role={} profile={} repairs={}",
                        runId, roleId, profile.name(), repairs);
                return new AcceptedAnswer(checked.value(), repairs, profile);
            }
            log.info("event=model_output_rejected runId={} role={} profile={} repairs={} issueCount={}",
                    runId, roleId, profile.name(), repairs, checked.messages().size());
            if (repairs == repairLimit) {
                throw new OutputInvalidException(checked.messages(), repairs);
            }
            previousAnswer = answer.text();
            messages = checked.messages();
            repairs++;
        }
    }

    private Invocation callWithRecovery(UUID runId, String roleId, ModelProfile profile,
                                        Function<ModelProfile, Prompt> promptFor, AttemptKind firstKind) {
        Prompt prompt = promptFor.apply(profile);
        for (int retries = 0; ; retries++) {
            AttemptKind kind = retries == 0 ? firstKind : AttemptKind.RETRY;
            try {
                return callOnce(runId, roleId, profile, prompt, kind);
            } catch (ModelCallException failure) {
                if (!failure.retryable()) {
                    throw unavailable(runId, roleId, profile, failure.reason());
                }
                if (retries == profile.retryLimit()) {
                    return tryFallback(runId, roleId, profile, promptFor, failure.reason());
                }
                waitBeforeRetry(runId, roleId, profile, retries + 1);
            }
        }
    }

    private Invocation tryFallback(UUID runId, String roleId, ModelProfile failedProfile,
                                   Function<ModelProfile, Prompt> promptFor, FailureReason lastReason) {
        ModelProfile fallback = profiles.fallback().orElse(null);
        if (!failedProfile.equals(profiles.primary()) || fallback == null) {
            throw unavailable(runId, roleId, failedProfile, lastReason);
        }
        // Rebuild the entire prompt for the fallback's window before any fallback call.
        Prompt prompt = promptFor.apply(fallback);
        log.info("event=model_fallback_selected runId={} role={} fromProfile={} toProfile={}",
                runId, roleId, failedProfile.name(), fallback.name());
        try {
            return callOnce(runId, roleId, fallback, prompt, AttemptKind.FALLBACK);
        } catch (ModelCallException failure) {
            throw unavailable(runId, roleId, fallback, failure.reason());
        }
    }

    private Invocation callOnce(UUID runId, String roleId, ModelProfile profile,
                                Prompt prompt, AttemptKind kind) {
        UUID reservationId = budget.reserve(runId, roleId, prompt.estimatedTokens(), profile.answerLimit());
        long started = System.nanoTime();
        try {
            ModelResult result = model.call(roleId, profile, prompt.systemText(), prompt.userText());
            Duration duration = result.duration() == null ? elapsed(started) : result.duration();
            return new Invocation(profile, prompt, kind, result, duration, reservationId);
        } catch (ModelCallException failure) {
            records.record(new Attempt(runId, roleId, profile, kind, prompt.systemText(), prompt.userText(),
                    null, Outcome.valueOf(failure.reason().name()), prompt.estimatedTokens(), null,
                    prompt.reductions(), elapsed(started)));
            settleReservation(reservationId);
            throw failure;
        }
    }

    private void settleReservation(UUID reservationId) {
        // A caller's transaction may still roll back the call record after record() returns.
        // Keep the durable reservation until that transaction has actually committed.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    budget.recorded(reservationId);
                }
            });
        } else {
            budget.recorded(reservationId);
        }
    }

    private void waitBeforeRetry(UUID runId, String roleId, ModelProfile profile, int retryNumber) {
        long delayMs = retryDelayMillis(retryNumber);
        log.info("event=model_retry_scheduled runId={} role={} profile={} retry={} delayMs={}",
                runId, roleId, profile.name(), retryNumber, delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model retry interrupted", exception);
        }
    }

    long retryDelayMillis(int retryNumber) {
        if (retryNumber < 1) {
            throw new IllegalArgumentException("retryNumber must be positive");
        }
        long delay = retryBaseDelay.toMillis();
        for (int index = 1; index < retryNumber; index++) {
            delay = Math.min(MAX_RETRY_DELAY.toMillis(), delay * 2);
        }
        return delay;
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - started));
    }

    private static ModelUnavailableException unavailable(UUID runId, String roleId,
                                                         ModelProfile profile, FailureReason reason) {
        log.info("event=model_unavailable runId={} role={} profile={} reason={}",
                runId, roleId, profile.name(), reason);
        return new ModelUnavailableException(reason);
    }

    private record Invocation(ModelProfile profile, Prompt prompt, AttemptKind kind,
                              ModelResult result, Duration duration, UUID reservationId) { }

    /** The validated answer, how many repairs it took, and the profile of the call that gave it. */
    public record AcceptedAnswer(JsonNode value, int repairAttempts, ModelProfile profile) { }

    public static final class ModelUnavailableException extends RuntimeException {
        private final FailureReason lastReason;

        private ModelUnavailableException(FailureReason lastReason) {
            super("MODEL_UNAVAILABLE: model call failed (" + lastReason + ")");
            this.lastReason = lastReason;
        }

        public String code() {
            return "MODEL_UNAVAILABLE";
        }

        public FailureReason lastReason() {
            return lastReason;
        }
    }

    public static final class OutputInvalidException extends RuntimeException {
        private final List<String> messages;
        private final int repairAttempts;

        private OutputInvalidException(List<String> messages, int repairAttempts) {
            super("OUTPUT_INVALID: model answer failed validation after " + repairAttempts + " repairs");
            this.messages = List.copyOf(messages);
            this.repairAttempts = repairAttempts;
        }

        public String code() {
            return "OUTPUT_INVALID";
        }

        public List<String> messages() {
            return messages;
        }

        public int repairAttempts() {
            return repairAttempts;
        }
    }
}
