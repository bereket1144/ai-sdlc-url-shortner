package io.github.bereket1144.agentcontroller.sandbox;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    @Override
    public Result run(List<String> command, Duration timeout) {
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
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Result(process.exitValue(), false);
            }
            process.destroyForcibly();
            return new Result(NO_EXIT_CODE, true);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a docker command");
        }
    }
}
