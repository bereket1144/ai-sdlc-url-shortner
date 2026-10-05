package io.github.bereket1144.agentcontroller.run;

/**
 * Thrown at a safe point when a reviewer has asked the run to stop. It is not a failure: the stage wrapper does not
 * retry it and does not mark the stage FAILED, and the dispatcher ends the attempt with {@link RunStatus#STOPPED}.
 */
public class RunStoppedException extends RuntimeException {

    private static final int MAX_CAUSE_DEPTH = 20; // stops a loop in a cause chain

    public RunStoppedException() {
        super("The run was asked to stop");
    }

    /** The graph library wraps a node failure, so a stop may sit anywhere in the cause chain. */
    public static boolean in(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof RunStoppedException) {
                return true;
            }
        }
        return false;
    }
}
