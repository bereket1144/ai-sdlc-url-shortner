package io.github.bereket1144.agentcontroller.modelcall;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.ReductionStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The record of every model call, so that a result can be traced to the call that made it and replayed. The caller
 * records one attempt after it knows how the attempt ended. A stored record is never changed: {@link #record} and
 * {@link #forRun} are the only operations, and there is no update or delete.
 */
@Service
public class ModelCallRecords {

    private static final Logger log = LoggerFactory.getLogger(ModelCallRecords.class);

    private final ModelCallRepository repository;
    private final RoleCatalog roles;

    ModelCallRecords(ModelCallRepository repository, RoleCatalog roles) {
        this.repository = repository;
        this.roles = roles;
    }

    /** Stores one attempt. It fails, and stores nothing, when the role is unknown or the run does not exist. */
    public void record(Attempt attempt) {
        String roleVersion = roles.get(attempt.role()).version();
        repository.save(new ModelCallEntity(attempt, roleVersion));
        // Ids and codes only: the prompt and the answer are stored, and never logged.
        log.info("event=model_call_recorded runId={} role={} attemptKind={} outcome={}",
                attempt.runId(), attempt.role(), attempt.kind(), attempt.outcome());
    }

    /** The run's attempts in the order they were recorded; empty when the run has none. */
    public List<StoredAttempt> forRun(UUID runId) {
        return repository.findByRunIdOrderByIdAsc(runId).stream().map(ModelCallEntity::toStoredAttempt).toList();
    }

    /** Why the attempt was made. */
    public enum AttemptKind { FIRST, REPAIR, RETRY, FALLBACK }

    /**
     * How an attempt ended. The caller decides it. The last six are the typed failures of the model call itself, and
     * have the same names as {@code ModelClient.FailureReason}.
     */
    public enum Outcome {
        SUCCESS, INVALID_ANSWER, ANSWER_CUT_OFF,
        UNREACHABLE, TIMED_OUT, RATE_LIMITED, SERVER_ERROR, REQUEST_REJECTED, RESPONSE_UNREADABLE
    }

    /**
     * Everything the caller knows about one attempt once it is over.
     *
     * @param runId                 the run the attempt belongs to
     * @param role                  the role id; the role's version is looked up and stored with the record
     * @param profile               the profile the call went to; its name and model identifier are stored
     * @param kind                  why the attempt was made
     * @param systemText            the system part of the prompt, as sent
     * @param userText              the user part of the prompt, as sent
     * @param result                what the model returned, or null when the call itself failed
     * @param outcome               how the attempt ended
     * @param estimatedPromptTokens the platform's estimate of the prompt size
     * @param estimatedAnswerTokens the platform's estimate of the answer size, or null when there is no answer
     * @param reductions            the steps taken to fit the prompt into the window; empty when none
     * @param duration              how long the attempt took; a failed call has no result to take this from
     */
    public record Attempt(UUID runId, String role, ModelProfile profile, AttemptKind kind, String systemText,
                          String userText, ModelResult result, Outcome outcome, long estimatedPromptTokens,
                          Long estimatedAnswerTokens, List<ReductionStep> reductions, Duration duration) { }

    /**
     * One stored attempt, as read back. Every field is what was stored. The reported token counts, the answer and the
     * finish reason are null when the call returned nothing; {@code reductionSteps} is the stored text, one
     * {@code index:KIND:version} entry per step joined by {@code ;}.
     */
    public record StoredAttempt(long id, UUID runId, String role, String roleVersion, String profile, String model,
                                AttemptKind kind, String inputFingerprint, String systemText, String userText,
                                String answerText, FinishReason finishReason, long estimatedPromptTokens,
                                Long estimatedAnswerTokens, Long reportedPromptTokens, Long reportedAnswerTokens,
                                String reductionSteps, long durationMs, Outcome outcome) { }
}
