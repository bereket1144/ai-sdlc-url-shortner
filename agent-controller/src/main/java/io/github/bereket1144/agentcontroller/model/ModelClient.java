package io.github.bereket1144.agentcontroller.model;

import java.time.Duration;

/** The provider-neutral call a stage uses; provider-specific HTTP stays behind this contract. */
public interface ModelClient {

    ModelResult call(String role, ModelProfile profile, String systemText, String userText);

    enum FinishReason { COMPLETE, ANSWER_LIMIT }

    record ModelResult(String text, FinishReason finishReason, Long promptTokens,
                       Long answerTokens, Duration duration) { }

    enum FailureReason {
        UNREACHABLE(true), TIMED_OUT(true), RATE_LIMITED(true), SERVER_ERROR(true),
        REQUEST_REJECTED(false), RESPONSE_UNREADABLE(false),
        /** Replay mode has no recorded answer for the call; it is never retried and never goes to a live model. */
        REPLAY_MISSING(false);

        private final boolean retryable;

        FailureReason(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    /** A safe, typed failure whose message cannot include a prompt, answer, or response body. */
    final class ModelCallException extends RuntimeException {
        private final FailureReason reason;

        public ModelCallException(FailureReason reason, String profileName) {
            super("Model call failed: " + reason + " (profile " + profileName + ")");
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }

        public boolean retryable() {
            return reason.retryable();
        }
    }
}
