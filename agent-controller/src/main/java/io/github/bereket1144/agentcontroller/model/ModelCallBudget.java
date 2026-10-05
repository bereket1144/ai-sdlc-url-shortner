package io.github.bereket1144.agentcontroller.model;

import java.util.List;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.StoredAttempt;
import io.github.bereket1144.agentcontroller.run.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomically reserves the configured run and role limits before each model attempt. */
@Service
public class ModelCallBudget {

    private static final Logger log = LoggerFactory.getLogger(ModelCallBudget.class);

    private final ModelCallRecords records;
    private final RunRepository runs;
    private final ModelBudgetReservationRepository reservations;
    private final int maxCallsPerRun;
    private final int maxCallsPerRole;
    private final long maxEstimatedTokensPerRun;

    public ModelCallBudget(ModelCallRecords records, RunRepository runs,
                           ModelBudgetReservationRepository reservations,
                           @Value("${agent.budget.max-calls-per-run:100}") int maxCallsPerRun,
                           @Value("${agent.budget.max-calls-per-role:20}") int maxCallsPerRole,
                           @Value("${agent.budget.max-estimated-tokens-per-run:200000}") long maxEstimatedTokensPerRun) {
        if (maxCallsPerRun <= 0 || maxCallsPerRole <= 0 || maxEstimatedTokensPerRun <= 0) {
            throw new IllegalArgumentException("agent.budget limits must be positive");
        }
        this.records = records;
        this.runs = runs;
        this.reservations = reservations;
        this.maxCallsPerRun = maxCallsPerRun;
        this.maxCallsPerRole = maxCallsPerRole;
        this.maxEstimatedTokensPerRun = maxEstimatedTokensPerRun;
    }

    /** Lock only the budget decision, then commit a durable reservation before the network call starts. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID reserve(UUID runId, String role, long estimatedPromptTokens, int answerLimit) {
        if (runId == null || role == null || role.isBlank() || estimatedPromptTokens < 0 || answerLimit <= 0) {
            throw new IllegalArgumentException("A budget check needs a run, role and non-negative token estimate");
        }
        runs.lockById(runId).orElseThrow(() -> new IllegalStateException("Run not found for model budget"));
        List<StoredAttempt> attempts = records.forRun(runId);
        List<ModelBudgetReservation> outstanding = reservations.findByRunId(runId);
        if ((long) attempts.size() + outstanding.size() >= maxCallsPerRun) {
            throw exhausted(runId, role, Limit.RUN_CALLS);
        }
        long roleCalls = attempts.stream().filter(attempt -> role.equals(attempt.role())).count()
                + outstanding.stream().filter(reservation -> role.equals(reservation.role())).count();
        if (roleCalls >= maxCallsPerRole) {
            throw exhausted(runId, role, Limit.ROLE_CALLS);
        }
        long remaining = maxEstimatedTokensPerRun;
        for (StoredAttempt attempt : attempts) {
            long answerTokens = attempt.estimatedAnswerTokens() == null ? 0 : attempt.estimatedAnswerTokens();
            if (attempt.estimatedPromptTokens() < 0 || answerTokens < 0
                    || attempt.estimatedPromptTokens() > remaining) {
                throw exhausted(runId, role, Limit.ESTIMATED_TOKENS);
            }
            remaining -= attempt.estimatedPromptTokens();
            if (answerTokens > remaining) {
                throw exhausted(runId, role, Limit.ESTIMATED_TOKENS);
            }
            remaining -= answerTokens;
        }
        for (ModelBudgetReservation reservation : outstanding) {
            if (reservation.estimatedPromptTokens() > remaining) {
                throw exhausted(runId, role, Limit.ESTIMATED_TOKENS);
            }
            remaining -= reservation.estimatedPromptTokens();
            if (reservation.reservedAnswerTokens() > remaining) {
                throw exhausted(runId, role, Limit.ESTIMATED_TOKENS);
            }
            remaining -= reservation.reservedAnswerTokens();
        }
        if (estimatedPromptTokens > remaining || answerLimit > remaining - estimatedPromptTokens) {
            throw exhausted(runId, role, Limit.ESTIMATED_TOKENS);
        }
        ModelBudgetReservation reservation = reservations.save(
                new ModelBudgetReservation(runId, role, estimatedPromptTokens, answerLimit));
        log.info("event=model_budget_reserved runId={} role={} reservationId={}", runId, role, reservation.id());
        return reservation.id();
    }

    /** A completed call is now charged by its immutable record, so its provisional charge can be removed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recorded(UUID reservationId) {
        reservations.deleteById(reservationId);
        log.info("event=model_budget_recorded reservationId={}", reservationId);
    }

    private static BudgetExhaustedException exhausted(UUID runId, String role, Limit limit) {
        log.info("event=model_budget_exhausted runId={} role={} limit={}", runId, role, limit);
        return new BudgetExhaustedException(limit);
    }

    public enum Limit { RUN_CALLS, ROLE_CALLS, ESTIMATED_TOKENS }

    public static final class BudgetExhaustedException extends RuntimeException {
        private final Limit limit;

        private BudgetExhaustedException(Limit limit) {
            super("BUDGET_EXHAUSTED: " + limit);
            this.limit = limit;
        }

        public String code() {
            return "BUDGET_EXHAUSTED";
        }

        public Limit limit() {
            return limit;
        }
    }
}
