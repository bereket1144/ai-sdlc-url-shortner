package io.github.bereket1144.agentcontroller.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Shared Git process handling for workspace commits and delivery into the controller checkout. */
final class GitCommands {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private GitCommands() { }

    static String run(Path directory, Map<String, String> environment, String... arguments) {
        Result result = execute(directory, environment, arguments);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + arguments[0] + " failed with exit code "
                    + result.exitCode() + ": " + result.output());
        }
        return result.output();
    }

    static Result execute(Path directory, Map<String, String> environment, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = null;
        Path output = null;
        try {
            // A merge can print more than a pipe holds. Redirecting to a temporary file keeps the timeout effective.
            output = Files.createTempFile("agent-controller-git-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().putAll(environment);
            process = builder.start();
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("git " + arguments[0] + " did not finish within " + TIMEOUT);
            }
            return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8).strip());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot run git " + arguments[0], exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git " + arguments[0], exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    // Cleanup must not race a Git process that can still be writing to the checkout.
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Git did not terminate after cancellation");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException exception) {
                    output.toFile().deleteOnExit();
                }
            }
        }
    }

    record Result(int exitCode, String output) { }
}
