package io.github.bereket1144.agentcontroller.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Runs one {@code docker} command line and says how it ended. It is the only place the platform starts a process,
 * so a fake can stand in for it in tests and the real Docker is never needed to check a command line.
 */
interface DockerCommand {

    /** The exit code when the command did not start or was killed for running too long or because it was cancelled. */
    int NO_EXIT_CODE = -1;

    /** Waits at most {@code timeout} for the command; a command that is still running then is killed. */
    default Result run(List<String> command, Duration timeout) {
        return run(command, timeout, () -> false);
    }

    /**
     * As above, and {@code cancelled} is asked now and then while the command runs: once it says true the command is
     * killed and the result has {@link #NO_EXIT_CODE} and is not timed out. The caller asks again to tell the two ways
     * of ending apart.
     */
    Result run(List<String> command, Duration timeout, BooleanSupplier cancelled);

    record Result(int exitCode, boolean timedOut) {

        boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }
}
