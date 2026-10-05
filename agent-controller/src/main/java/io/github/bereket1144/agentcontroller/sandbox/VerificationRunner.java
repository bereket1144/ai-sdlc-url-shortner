package io.github.bereket1144.agentcontroller.sandbox;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Builds and tests one committed workspace somewhere it cannot harm the controller. */
public interface VerificationRunner {

    /** Verification without a stop request, for callers that do not execute a managed run. */
    default VerificationResult verify(Path workspace, String commitId) {
        return verify(workspace, commitId, () -> false);
    }

    /**
     * Builds and tests the workspace folder, which holds the given commit.
     *
     * <p>A report that is missing, unreadable or late is not an error: it is returned as
     * {@link VerificationResult.Outcome#NO_RESULT}, which callers must treat as a failure. An exception means the
     * call itself was wrong, for example a folder that does not exist.
     *
     * @param cancelled asked now and then while the build runs; once it says true the build is stopped and the result
     *                  is NO_RESULT, which the caller must not mistake for a build that failed
     */
    VerificationResult verify(Path workspace, String commitId, BooleanSupplier cancelled);
}
