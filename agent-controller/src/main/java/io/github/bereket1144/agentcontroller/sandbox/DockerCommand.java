package io.github.bereket1144.agentcontroller.sandbox;

import java.time.Duration;
import java.util.List;

/**
 * Runs one {@code docker} command line and says how it ended. It is the only place the platform starts a process,
 * so a fake can stand in for it in tests and the real Docker is never needed to check a command line.
 */
interface DockerCommand {

    /** The exit code when the command did not start or was killed for running too long. */
    int NO_EXIT_CODE = -1;

    /** Waits at most {@code timeout} for the command; a command that is still running then is killed. */
    Result run(List<String> command, Duration timeout);

    record Result(int exitCode, boolean timedOut) {

        boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }
}
