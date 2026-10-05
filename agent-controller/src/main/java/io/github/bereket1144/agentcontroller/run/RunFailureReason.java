package io.github.bereket1144.agentcontroller.run;

import io.github.bereket1144.agentcontroller.model.ModelCallBudget.BudgetExhaustedException;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.ContextTooLargeException;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.ModelUnavailableException;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.OutputInvalidException;

/** Stable reviewer-facing failure codes and messages. Exception messages are never exposed by the run API. */
public enum RunFailureReason {
    OUTPUT_INVALID("The model answer did not pass validation."),
    MODEL_UNAVAILABLE("The model could not complete the request."),
    CONTEXT_TOO_LARGE("The input did not fit the model context window."),
    BUDGET_EXHAUSTED("The run's model-call budget was exhausted."),
    REPLAY_MISSING("A required replay answer was missing."),
    ACCESS_DENIED("The requested action was not permitted."),
    REJECTED("A required check rejected the result."),
    ASSEMBLY_CONFLICT("The generated changes could not be assembled."),
    STOPPED_BY_REVIEWER("A reviewer stopped the run."),
    STAGE_TIMEOUT("A stage exceeded its time limit."),
    RECOVERY_FAILED("The run could not be recovered."),
    UNEXPECTED("The run stopped after an unexpected error.");

    private final String message;

    RunFailureReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    /** Graph execution can wrap the original failure, so inspect the bounded cause chain. */
    public static RunFailureReason from(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
            if (cause instanceof OutputInvalidException) {
                return OUTPUT_INVALID;
            }
            if (cause instanceof ModelUnavailableException) {
                return MODEL_UNAVAILABLE;
            }
            if (cause instanceof ContextTooLargeException) {
                return CONTEXT_TOO_LARGE;
            }
            if (cause instanceof BudgetExhaustedException) {
                return BUDGET_EXHAUSTED;
            }
        }
        return UNEXPECTED;
    }
}
