package io.github.bereket1144.agentcontroller.modelcall;

import java.util.UUID;

import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.AttemptKind;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Outcome;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.StoredAttempt;

/** Builds stored attempts for the tests of recording and replay. */
final class StoredAttempts {

    private StoredAttempts() {
    }

    /** An attempt that returned an answer; the fingerprint is the one replay matches on. */
    static StoredAttempt answered(String role, String fingerprint, Outcome outcome, String answer,
                                  FinishReason finishReason) {
        return new StoredAttempt(1, UUID.randomUUID(), role, "1", "local", "model", AttemptKind.FIRST,
                fingerprint, "system", "user", answer, finishReason, 100, 20L, 90L, 18L, "", 1500, outcome);
    }

    /** An attempt whose call failed, so it has no answer, finish reason or reported counts. */
    static StoredAttempt failed(String role, String fingerprint, Outcome outcome) {
        return new StoredAttempt(2, UUID.randomUUID(), role, "1", "local", "model", AttemptKind.RETRY,
                fingerprint, "system", "user", null, null, 100, null, null, null, "", 30000, outcome);
    }
}
