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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The workspace of one run: a new, empty git repository made with {@code git init} in {@code <base>/<run-id>}, on the
 * branch {@code main}, where a change set is written and committed. It uses the {@code git} command line and runs
 * nothing from the workspace. The controller's own checkout is never read or changed. It exists only when
 * {@code agent.stages.development=agent}, so a run without the development agent needs no workspace setting.
 */
@Service
@ConditionalOnProperty(name = "agent.stages.development", havingValue = "agent")
public class RunWorkspace {

    private static final Logger log = LoggerFactory.getLogger(RunWorkspace.class);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(60);
    private static final String DEVELOPMENT = "development";

    private final Path base;

    /** @param base the folder that holds one folder per run; created when the first workspace is made */
    public RunWorkspace(@Value("${workspace.base}") String base) {
        if (base.isBlank()) {
            throw new IllegalArgumentException("workspace.base must not be blank");
        }
        this.base = Path.of(base).toAbsolutePath().normalize();
    }

    /** The folder of the run's workspace. It is made with {@code git init} on the first call and reused after that. */
    public Path create(UUID runId) {
        Path root = base.resolve(runId.toString());
        if (Files.isDirectory(root.resolve(".git"))) {
            log.info("event=workspace_reused runId={}", runId);
            return root;
        }
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot create the workspace folder", exception);
        }
        git(root, Map.of(), "init", "--quiet", "--initial-branch=main");
        log.info("event=workspace_created runId={} branch=main", runId);
        return root;
    }

    /** The id of the commit that the run's branch is on; fails when nothing has been committed yet. */
    public String head(UUID runId) {
        return git(create(runId), Map.of(), "rev-parse", "HEAD");
    }

    /**
     * Writes every file of the change set into the run's workspace and makes one commit by the development role.
     * Nothing is written unless every file is allowed.
     *
     * @return the id of the new commit
     * @throws IllegalArgumentException when a path is not allowed or goes through a link; names the change by number
     */
    public String commit(UUID runId, ChangeSet changes) {
        Path root = stage(runId, changes);
        return commitStaged(runId, root, changes.files().size(), DEVELOPMENT,
                "development: apply " + changes.files().size() + " file changes for run " + runId);
    }

    /**
     * Writes every file of the change set, replacing a file that is already at its path, and makes one commit by the
     * given author, unless every file already holds this content. Nothing is written unless every file is allowed.
     *
     * @param author the author and committer name; the address is a fixed placeholder built from it
     * @return the id of the new commit, or empty when the files were unchanged and so nothing was committed
     * @throws IllegalArgumentException when a path is not allowed or goes through a link; names the change by number
     */
    public Optional<String> commitIfChanged(UUID runId, ChangeSet changes, String author, String message) {
        Path root = stage(runId, changes);
        if (git(root, Map.of(), "diff", "--cached", "--name-only").isEmpty()) {
            log.info("event=change_set_unchanged runId={} files={} author={}", runId, changes.files().size(), author);
            return Optional.empty();
        }
        return Optional.of(commitStaged(runId, root, changes.files().size(), author, message));
    }

    /** Checks every path, then writes the files and adds them to the index of the run's repository. */
    private Path stage(UUID runId, ChangeSet changes) {
        Path root = create(runId);
        List<FileChange> files = changes.files();
        for (int index = 0; index < files.size(); index++) {
            String path = files.get(index).path();
            String problem = ChangeSet.pathProblem(path);
            if (problem == null && goesThroughLink(root, path)) {
                problem = "goes through a link";
            }
            if (problem != null) {
                log.warn("event=change_set_refused runId={} change={} reason=\"{}\"", runId, index + 1, problem);
                throw new IllegalArgumentException("Change " + (index + 1) + " " + problem);
            }
        }
        // --force: a .gitignore, also one of this same change set, must not keep a file of the change set out of the
        // commit; without it git refuses to add an ignored file and the run would end with an unexpected error.
        List<String> add = new ArrayList<>(List.of("add", "--force", "--"));
        for (FileChange file : files) {
            write(root.resolve(file.path()), file.content());
            add.add(file.path());
        }
        // A path is a literal name, never a pattern: "*.py" must add only a file called "*.py".
        git(root, Map.of("GIT_LITERAL_PATHSPECS", "1"), add.toArray(String[]::new));
        return root;
    }

    private static String commitStaged(UUID runId, Path root, int fileCount, String author, String message) {
        git(root, identity(author), "commit", "--quiet", "--no-gpg-sign", "-m", message);
        String commitId = git(root, Map.of(), "rev-parse", "HEAD");
        log.info("event=change_set_committed runId={} files={} commit={} author={}", runId, fileCount, commitId, author);
        return commitId;
    }

    /** The role is the author and the committer; the address is a fixed placeholder and never the user's own. */
    private static Map<String, String> identity(String name) {
        String address = name + "@agent.invalid";
        return Map.of("GIT_AUTHOR_NAME", name, "GIT_AUTHOR_EMAIL", address,
                "GIT_COMMITTER_NAME", name, "GIT_COMMITTER_EMAIL", address);
    }

    /** True when the file or any folder on the way to it is a link, which could lead out of the workspace. */
    private static boolean goesThroughLink(Path root, String path) {
        Path current = root;
        for (Path part : Path.of(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static void write(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write a file of the change set", exception);
        }
    }

    /** Runs one git command and returns what it printed; fails with the exit code and git's own message. */
    private static String git(Path directory, Map<String, String> environment, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        // Never wait for a question that nobody can answer.
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().putAll(environment);
        try {
            Process process = builder.start();
            // The commands are quiet, so their output stays far below the pipe size and can be read afterwards.
            if (!process.waitFor(GIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git " + arguments[0] + " did not finish within " + GIT_TIMEOUT);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                throw new IllegalStateException(
                        "git " + arguments[0] + " failed with exit code " + process.exitValue() + ": " + error);
            }
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot run git " + arguments[0], exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git " + arguments[0], exception);
        }
    }
}
