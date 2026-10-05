package io.github.bereket1144.agentcontroller.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// No Docker and no Spring: a fake stands in for the docker command line and records what it was asked to run.
class DockerVerificationRunnerTest {

    private static final SandboxProperties PROPERTIES = new SandboxProperties(
            "sandbox-image", "run-the-tests", "/tmp/junit.xml", "1g", 1, 256, Duration.ofMinutes(5));
    private static final String PASSING_REPORT = "<testsuite><testcase classname=\"t\" name=\"one\"/></testsuite>";

    @TempDir
    Path workspace;

    @Test
    @DisplayName("AC-AGT-62.8 the build runs in a new labelled container with no network, no capabilities, no new "
            + "privileges, a non-root user and limits, gets the workspace by copy, returns only its report by copy "
            + "and is removed afterwards")
    void containerIsLockedDownCopiedInAndOutAndRemoved() {
        FakeDocker docker = new FakeDocker(PASSING_REPORT, false);

        runnerWith(docker).verify(workspace, "abc123");

        List<List<String>> commands = docker.commands();
        assertThat(commands).hasSize(5);

        List<String> create = commands.get(0);
        String container = create.get(create.indexOf("--name") + 1);
        assertThat(create)
                .startsWith("docker", "create")
                .containsSequence("--network", "none")
                .containsSequence("--user", "10001:10001")
                .containsSequence("--cap-drop", "ALL")
                .containsSequence("--security-opt", "no-new-privileges")
                .containsSequence("--memory", "1g")
                .containsSequence("--cpus", "1.0")
                .containsSequence("--pids-limit", "256")
                .containsSequence("--label", DockerVerificationRunner.LABEL)
                .contains("sandbox-image")
                .doesNotContain("-v", "--volume", "--mount");
        // The workspace goes in by copy, the one report comes out by copy, and nothing else is copied.
        assertThat(commands.get(1))
                .containsExactly("docker", "cp", workspace.toAbsolutePath() + "/.", container + ":/workspace");
        assertThat(commands.get(2)).containsExactly("docker", "start", "-a", container);
        assertThat(commands.get(3)).hasSize(4).startsWith("docker", "cp", container + ":/tmp/junit.xml");
        assertThat(commands.get(4)).containsExactly("docker", "rm", "-f", container);
        // The time limit is the one on the command that builds and tests.
        assertThat(docker.timeouts().get(2)).isEqualTo(PROPERTIES.timeout());
    }

    @Test
    @DisplayName("AC-AGT-62.9 a missing, unreadable or timed-out report is NO_RESULT, which is a failure, "
            + "and the container is still removed")
    void noReportIsNoResult() {
        assertNoResult(new FakeDocker(null, false));
        assertNoResult(new FakeDocker("this is not xml", false));
        assertNoResult(new FakeDocker(PASSING_REPORT, true));
    }

    @Test
    @DisplayName("AC-AGT-43.1 a stop during a build cancels its wait, removes the container and does not read a report")
    void stopsARunningBuildAndRemovesItsContainer() {
        AtomicBoolean stopped = new AtomicBoolean();
        List<List<String>> commands = new ArrayList<>();
        DockerCommand docker = (command, timeout, cancelled) -> {
            commands.add(command);
            if (command.get(1).equals("start")) {
                // A reviewer stops the run while the attached Docker command is waiting for the build.
                stopped.set(true);
                assertThat(cancelled.getAsBoolean()).isTrue();
                return new DockerCommand.Result(DockerCommand.NO_EXIT_CODE, false);
            }
            return new DockerCommand.Result(0, false);
        };

        VerificationResult result = runnerWith(docker).verify(workspace, "abc123", stopped::get);

        assertThat(result.outcome()).isEqualTo(Outcome.NO_RESULT);
        assertThat(commands).hasSize(4);
        assertThat(commands.getLast()).startsWith("docker", "rm", "-f");
        assertThat(commands).noneMatch(command -> command.get(1).equals("cp") && command.get(2).contains(":/"));
    }

    private void assertNoResult(FakeDocker docker) {
        VerificationResult result = runnerWith(docker).verify(workspace, "abc123");

        assertThat(result.outcome()).isEqualTo(Outcome.NO_RESULT);
        assertThat(result.passed()).isFalse();
        assertThat(result.commitId()).isEqualTo("abc123");
        assertThat(docker.commands().getLast()).startsWith("docker", "rm", "-f");
    }

    private static DockerVerificationRunner runnerWith(DockerCommand docker) {
        return new DockerVerificationRunner(PROPERTIES, docker);
    }

    /** Succeeds at every step. {@code report} is what the container's report file holds; null means it has none. */
    private static final class FakeDocker implements DockerCommand {

        private final String report;
        private final boolean startTimesOut;
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();

        FakeDocker(String report, boolean startTimesOut) {
            this.report = report;
            this.startTimesOut = startTimesOut;
        }

        List<List<String>> commands() {
            return commands;
        }

        List<Duration> timeouts() {
            return timeouts;
        }

        @Override
        public Result run(List<String> command, Duration timeout, BooleanSupplier cancelled) {
            commands.add(command);
            timeouts.add(timeout);
            String step = command.get(1);
            if (step.equals("start") && startTimesOut) {
                return new Result(NO_EXIT_CODE, true);
            }
            // A copy whose source is "<container>:<path>" is a copy out; the host path is the last word.
            if (step.equals("cp") && command.get(2).contains(":/")) {
                if (report == null) {
                    return new Result(1, false);
                }
                try {
                    Files.writeString(Path.of(command.get(3)), report);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
            return new Result(0, false);
        }
    }
}
