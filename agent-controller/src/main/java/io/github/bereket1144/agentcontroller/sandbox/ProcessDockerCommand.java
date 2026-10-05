package io.github.bereket1144.agentcontroller.sandbox;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Starts the command as a process of the controller. Its output is thrown away: what the tests print comes from
 * generated code and is never logged, and the reports are read from the files that are copied out.
 */
@Component
class ProcessDockerCommand implements DockerCommand {

    private static final Logger log = LoggerFactory.getLogger(ProcessDockerCommand.class);
    /** How often a running command is checked for a cancel request: the most a stop waits for the command. */
    static final Duration POLL = Duration.ofSeconds(1);

    @Override
    public Result run(List<String> command, Duration timeout, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            return new Result(NO_EXIT_CODE, false);
        }
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException failure) {
            // Usually "docker" is not installed or not on the path. The caller turns this into NO_RESULT.
            log.error("event=docker_not_started errorType={} message={}",
                    failure.getClass().getName(), failure.getMessage());
            return new Result(NO_EXIT_CODE, false);
        }

        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return new Result(NO_EXIT_CODE, true);
                }
                if (process.waitFor(Math.min(left, POLL.toNanos()), TimeUnit.NANOSECONDS)) {
                    return new Result(process.exitValue(), false);
                }
                if (cancelled.getAsBoolean()) {
                    return new Result(NO_EXIT_CODE, false);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a docker command");
        } finally {
            // A command that is still running here ran over its time, was cancelled or was interrupted: kill it. For
            // "docker start -a" this ends the client only; the caller removes the container with "docker rm -f".
            process.destroyForcibly();
        }
    }
}
