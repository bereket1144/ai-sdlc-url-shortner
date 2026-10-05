package io.github.bereket1144.agentcontroller.sandbox;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

/**
 * Builds and tests a workspace in a new container, through the {@code docker} command line.
 *
 * <p>The steps are: create the container (non-root, no network, limits, a label that names the platform), copy the
 * workspace in, start it and wait, copy the one report file out, and remove the container. No folder of the host is
 * mounted. {@code --rm} is not used: Docker would delete the container the moment it exits, before the report
 * could be copied out, so the container is removed by an explicit {@code docker rm -f} in a {@code finally} block.
 * That command also kills a container that ran over its time limit.
 */
@Component
@EnableConfigurationProperties(SandboxProperties.class)
public class DockerVerificationRunner implements VerificationRunner {

    /** Marks every container this platform starts, so that a container left behind can be found and removed. */
    static final String LABEL = "io.github.bereket1144.agentcontroller.sandbox=true";

    private static final Logger log = LoggerFactory.getLogger(DockerVerificationRunner.class);

    private static final String WORKSPACE_IN_CONTAINER = "/workspace";
    private static final String USER = "10001:10001";
    /** The limit for the quick steps (create, copy, remove); the time limit of the tests is configured. */
    private static final Duration STEP_LIMIT = Duration.ofMinutes(2);
    /** A report longer than this is cut and so no longer parses; a test report is far smaller. */
    private static final int MAX_REPORT_BYTES = 5 * 1024 * 1024;

    private final SandboxProperties properties;
    private final DockerCommand docker;

    DockerVerificationRunner(SandboxProperties properties, DockerCommand docker) {
        this.properties = properties;
        this.docker = docker;
    }

    @Override
    public VerificationResult verify(Path workspace, String commitId) {
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(commitId, "commitId must not be null");
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("The workspace is not a folder: " + workspace);
        }

        String container = "verify-" + UUID.randomUUID();
        Path reportFolder = createReportFolder();
        long started = System.nanoTime();
        VerificationResult result = VerificationResult.noResult(commitId);
        log.info("event=verification_started commitId={} container={} image={}",
                commitId, container, properties.image());
        try {
            result = runInContainer(container, workspace, commitId, reportFolder);
        } finally {
            succeeds("remove", container, List.of("docker", "rm", "-f", container), STEP_LIMIT);
            deleteReportFolder(reportFolder);
            log.info("event=verification_finished commitId={} container={} outcome={} testsRun={} "
                            + "testsFailed={} durationMs={}",
                    commitId, container, result.outcome(), result.testsRun(), result.testsFailed(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        }
        return result;
    }

    private VerificationResult runInContainer(String container, Path workspace, String commitId, Path reportFolder) {
        boolean ready = succeeds("create", container, createCommand(container), STEP_LIMIT)
                && succeeds("copy_in", container, List.of("docker", "cp",
                        workspace.toAbsolutePath() + "/.", container + ":" + WORKSPACE_IN_CONTAINER), STEP_LIMIT);
        if (!ready) {
            return VerificationResult.noResult(commitId);
        }

        DockerCommand.Result run = docker.run(List.of("docker", "start", "-a", container), properties.timeout());
        if (run.timedOut()) {
            log.warn("event=verification_timed_out commitId={} container={} limit={}",
                    commitId, container, properties.timeout());
            return VerificationResult.noResult(commitId);
        }
        // The command's own exit code is not the verdict: a failing test makes it non-zero. The report decides.
        log.info("event=verification_container_exited commitId={} container={} exitCode={}",
                commitId, container, run.exitCode());

        Path report = reportFolder.resolve("report.xml");
        if (!succeeds("copy_out", container,
                List.of("docker", "cp", container + ":" + properties.reportPath(), report.toString()), STEP_LIMIT)) {
            return VerificationResult.noResult(commitId);
        }
        return parseReport(report, commitId);
    }

    private List<String> createCommand(String container) {
        return List.of("docker", "create",
                "--name", container,
                "--label", LABEL,
                "--user", USER,
                "--network", "none",
                "--memory", properties.memory(),
                // Without a swap limit equal to the memory limit, a container may use twice the memory.
                "--memory-swap", properties.memory(),
                "--cpus", String.valueOf(properties.cpus()),
                "--pids-limit", String.valueOf(properties.pidsLimit()),
                properties.image(), "sh", "-c", properties.command());
    }

    private boolean succeeds(String step, String container, List<String> command, Duration limit) {
        DockerCommand.Result result = docker.run(command, limit);
        if (!result.succeeded()) {
            log.warn("event=sandbox_step_failed step={} container={} exitCode={} timedOut={}",
                    step, container, result.exitCode(), result.timedOut());
        }
        return result.succeeded();
    }

    private VerificationResult parseReport(Path report, String commitId) {
        // The file was copied out of a container that ran generated code. NOFOLLOW_LINKS refuses a link in its
        // place, which could otherwise point at a file of the host.
        try (InputStream input = Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS)) {
            return JunitReportParser.parse(new String(input.readNBytes(MAX_REPORT_BYTES), UTF_8), commitId);
        } catch (IOException failure) {
            log.warn("event=verification_report_unreadable commitId={} errorType={}",
                    commitId, failure.getClass().getName());
            return VerificationResult.noResult(commitId);
        }
    }

    private static Path createReportFolder() {
        try {
            return Files.createTempDirectory("verification-report-");
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot create a folder for the test report", failure);
        }
    }

    private static void deleteReportFolder(Path reportFolder) {
        try {
            FileSystemUtils.deleteRecursively(reportFolder);
        } catch (IOException failure) {
            log.warn("event=sandbox_cleanup_failed folder={} errorType={}",
                    reportFolder, failure.getClass().getName());
        }
    }
}
