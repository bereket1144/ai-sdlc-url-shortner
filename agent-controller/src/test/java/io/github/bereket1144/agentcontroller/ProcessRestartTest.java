package io.github.bereket1144.agentcontroller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts real JVMs ({@link ApprovalRunProcess}) against a real PostgreSQL, with fake tasks and no
 * model calls. The first JVM runs a workflow up to the approval gate and is then killed (SIGKILL on
 * Unix, so it gets no chance to clean up). A second JVM, with nothing in common with the first
 * except the database, finds the run waiting at the gate.
 *
 * <p>This is the only test that proves recovery after a process dies; the others reuse one JVM.
 */
@Testcontainers
class ProcessRestartTest {

    private static final long WAIT_SECONDS = 60;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    @Test
    void aRunPausedInOneJvmIsFoundAtTheGateByAnotherAfterTheFirstIsKilled() throws Exception {
        String runId = "run-" + UUID.randomUUID();

        Jvm first = Jvm.start("pause", runId);
        try {
            first.awaitPaused();
            first.process.destroyForcibly();
            assertThat(first.process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)).as("first JVM was killed").isTrue();
            assertThat(first.process.exitValue()).as("first JVM did not exit by itself").isNotEqualTo(0);
        } finally {
            first.process.destroyForcibly();
        }
        assertThat(first.linesWith("EXECUTED ")).containsExactly("EXECUTED draft");

        Jvm second = Jvm.start("recover", runId);
        assertThat(second.process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)).as("second JVM finished").isTrue();
        second.awaitOutputEnd();

        assertThat(second.process.exitValue()).as("second JVM output: %s", second.lines).isEqualTo(0);
        assertThat(second.linesWith("RECOVERED ")).containsExactly("RECOVERED next=approval-gate draft=draft-v1");
        assertThat(second.linesWith("EXECUTED ")).as("recovering ran no task").isEmpty();

        // Three different operating-system processes were involved.
        assertThat(List.of(first.pid(), second.pid(), ProcessHandle.current().pid())).doesNotHaveDuplicates();
    }

    /** A JVM running {@link ApprovalRunProcess}, with the lines it prints collected as they arrive. */
    private static final class Jvm {

        final Process process;
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        private final CompletableFuture<Void> outputEnded = new CompletableFuture<>();
        private final CompletableFuture<Void> paused = new CompletableFuture<>();

        private Jvm(Process process) {
            this.process = process;
            Thread reader = new Thread(this::collectOutput, "output-of-" + process.pid());
            reader.setDaemon(true);
            reader.start();
        }

        static Jvm start(String mode, String runId) throws IOException {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process process = new ProcessBuilder(
                            java, "-cp", System.getProperty("java.class.path"), ApprovalRunProcess.class.getName(),
                            mode, runId, POSTGRES.getHost(), String.valueOf(POSTGRES.getMappedPort(5432)),
                            POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .redirectErrorStream(true)
                    .start();
            return new Jvm(process);
        }

        private void collectOutput() {
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    lines.add(line);
                    if (line.equals("PAUSED")) {
                        paused.complete(null);
                    }
                }
            } catch (IOException e) {
                lines.add("could not read output: " + e);
            } finally {
                // If the JVM ended before saying PAUSED, fail fast with what it printed.
                paused.completeExceptionally(new IllegalStateException("JVM ended without PAUSED: " + lines));
                outputEnded.complete(null);
            }
        }

        void awaitPaused() throws Exception {
            paused.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        void awaitOutputEnd() throws Exception {
            outputEnded.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        List<String> linesWith(String prefix) {
            synchronized (lines) {
                return lines.stream().filter(line -> line.startsWith(prefix)).toList();
            }
        }

        long pid() {
            return process.pid();
        }
    }
}
