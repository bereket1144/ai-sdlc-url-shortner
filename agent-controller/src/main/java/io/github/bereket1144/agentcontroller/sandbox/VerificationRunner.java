package io.github.bereket1144.agentcontroller.sandbox;

import java.nio.file.Path;

/** Builds and tests one committed workspace somewhere it cannot harm the controller. */
public interface VerificationRunner {

    /**
     * Builds and tests the workspace folder, which holds the given commit.
     *
     * <p>A report that is missing, unreadable or late is not an error: it is returned as
     * {@link VerificationResult.Outcome#NO_RESULT}, which callers must treat as a failure. An exception means the
     * call itself was wrong, for example a folder that does not exist.
     */
    VerificationResult verify(Path workspace, String commitId);
}
