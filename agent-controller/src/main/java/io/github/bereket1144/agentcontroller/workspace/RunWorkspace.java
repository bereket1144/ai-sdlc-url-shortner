package io.github.bereket1144.agentcontroller.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The workspace of one run: a git worktree of the platform repository in {@code <base>/<run-id>}, on a new branch
 * {@code run/<run-id>}, where a change set is written and committed. The branch is cut from a base ref, or, for a run
 * that changes an earlier run, from that run's branch, so its commits stack on top of the earlier history. Only the
 * product folder and the run's evidence folder {@code runs/<run-id>} are checked out; the agents' files are written
 * under the product folder. It uses the {@code git} command line and runs nothing from the workspace. The branch of
 * the platform repository that is checked out, its index and its working files are never changed, and nothing is
 * merged, pushed, pruned or deleted. It exists only when {@code agent.stages.development=agent}, so a run without the
 * development agent needs no workspace setting.
 */
@Service
@ConditionalOnProperty(name = "agent.stages.development", havingValue = "agent")
public class RunWorkspace {

    private static final Logger log = LoggerFactory.getLogger(RunWorkspace.class);
    private static final String DEVELOPMENT = "development";
    private static final String BRANCH_PREFIX = "run/";
    private static final String EVIDENCE_FOLDER = "runs";
    private static final ChangeSet NONE = new ChangeSet(List.of());

    private final Path base;
    private final Path repository;
    private final String baseRef;
    private final String product;

    /**
     * @param base          the folder that holds one folder per run; made by git when the first workspace is added
     * @param repository    the root of the platform repository, which the workspaces are worktrees of
     * @param baseRef       the commit that the branch of a run is cut from, unless it changes an earlier run
     * @param productFolder the folder of the product, relative to the root of the repository
     * @throws IllegalArgumentException when a setting is blank or cannot be what it names
     */
    public RunWorkspace(@Value("${workspace.base}") String base,
                        @Value("${workspace.repository}") String repository,
                        @Value("${workspace.base-ref:HEAD}") String baseRef,
                        @Value("${workspace.product-folder}") String productFolder) {
        if (base.isBlank()) {
            throw new IllegalArgumentException("workspace.base must not be blank");
        }
        if (repository.isBlank()) {
            throw new IllegalArgumentException("workspace.repository must not be blank");
        }
        if (baseRef.isBlank() || baseRef.startsWith("-")) {
            throw new IllegalArgumentException("workspace.base-ref must be a commit name, not blank or an option");
        }
        String problem = ChangeSet.pathProblem(productFolder);
        if (problem != null) {
            throw new IllegalArgumentException("workspace.product-folder " + problem);
        }
        this.base = Path.of(base).toAbsolutePath().normalize();
        this.repository = Path.of(repository).toAbsolutePath().normalize();
        // A folder inside a repository would let git find the repository above it and make branches there.
        if (!Files.exists(this.repository.resolve(".git"))) {
            throw new IllegalArgumentException(
                    "workspace.repository is not the root of a git repository: " + this.repository);
        }
        this.baseRef = baseRef;
        this.product = String.join("/", ChangeSet.parts(productFolder));
    }

    /** The folder of the run's workspace. It is added as a worktree on the first call and reused after that. */
    public Path create(UUID runId) {
        return create(runId, null);
    }

    /**
     * As {@link #create(UUID)}, except that a new branch is cut from the branch of the run {@code baseRunId} when there
     * is one. The only branch cut from is that run's own, found by its id; no other name can be given. A workspace that
     * already exists is reused whatever the base, and a branch that already exists is checked out again.
     *
     * @param baseRunId the run to start from, or null to start from the base ref
     * @throws IllegalStateException when the base run's branch does not exist; nothing is created then
     */
    public Path create(UUID runId, UUID baseRunId) {
        Path root = base.resolve(runId.toString());
        if (Files.exists(root.resolve(".git"))) {
            log.info("event=workspace_reused runId={}", runId);
            return root;
        }
        String branch = BRANCH_PREFIX + runId;
        if (branchExists(branch)) {
            git(repository, Map.of(), "worktree", "add", "--no-checkout", root.toString(), branch);
            log.info("event=workspace_created runId={} branch={} start=existing_branch", runId, branch);
        } else {
            String start = baseRunId == null ? baseRef : startOfChange(runId, baseRunId);
            git(repository, Map.of(), "worktree", "add", "--no-checkout", "-b", branch, root.toString(), start);
            log.info("event=workspace_created runId={} branch={} start={}", runId, branch, start);
        }
        // "worktree add --no-checkout" leaves the index empty, so the files are checked out here: the sparse checkout
        // keeps only the product and the evidence folders in the folder, and read-tree fills the index with every file
        // of the branch, so that a later commit keeps the files that are not checked out.
        git(root, Map.of(), "sparse-checkout", "set", "--no-cone", "/" + product + "/",
                "/" + EVIDENCE_FOLDER + "/" + runId + "/");
        git(root, Map.of(), "read-tree", "-mu", "HEAD");
        return root;
    }

    private String startOfChange(UUID runId, UUID baseRunId) {
        String baseBranch = BRANCH_PREFIX + baseRunId;
        if (!branchExists(baseBranch)) {
            log.warn("event=workspace_base_missing runId={} baseRunId={}", runId, baseRunId);
            throw new IllegalStateException("The branch of the base run does not exist");
        }
        return "refs/heads/" + baseBranch;
    }

    private boolean branchExists(String branch) {
        String ref = "refs/heads/" + branch;
        return git(repository, Map.of(), "for-each-ref", "--format=%(refname)", ref).equals(ref);
    }

    /** The folder of the run's product in its workspace: what the sandbox is given and what the agents are shown. */
    public Path productFolder(UUID runId) {
        return create(runId).resolve(product);
    }

    /** The id of the commit that the run's branch is on. */
    public String head(UUID runId) {
        return git(create(runId), Map.of(), "rev-parse", "HEAD");
    }

    /** Delivery uses the same validated repository and product folder as workspace creation. */
    Path repository() {
        return repository;
    }

    String productPath() {
        return product;
    }

    /**
     * Writes every file of the change set into the product folder of the run's workspace and makes one commit by the
     * development role. Nothing is written unless every file is allowed.
     *
     * @return the id of the new commit
     * @throws IllegalArgumentException when a path is not allowed or goes through a link; names the change by number
     */
    public String commit(UUID runId, ChangeSet changes) {
        Path root = stage(runId, changes, NONE);
        return commitStaged(runId, root, changes.files().size(), DEVELOPMENT,
                "development: apply " + changes.files().size() + " file changes for run " + runId);
    }

    /** As {@link #commitIfChanged(UUID, ChangeSet, ChangeSet, String, String)} with no evidence. */
    public Optional<String> commitIfChanged(UUID runId, ChangeSet changes, String author, String message) {
        return commitIfChanged(runId, changes, NONE, author, message);
    }

    /**
     * Writes every file of the change set under the product folder and every file of the evidence under the run's own
     * evidence folder {@code runs/<run-id>}, replacing a file that is already at its path, and makes one commit by the
     * given author, unless every file already holds this content. Nothing is written unless every file is allowed.
     *
     * @param author the author and committer name; the address is a fixed placeholder built from it
     * @return the id of the new commit, or empty when the files were unchanged and so nothing was committed
     * @throws IllegalArgumentException when a path is not allowed or goes through a link; names the change by number,
     *                                  counting the evidence after the change set
     */
    public Optional<String> commitIfChanged(UUID runId, ChangeSet changes, ChangeSet evidence, String author,
                                            String message) {
        Path root = stage(runId, changes, evidence);
        int fileCount = changes.files().size() + evidence.files().size();
        if (git(root, Map.of(), "diff", "--cached", "--name-only").isEmpty()) {
            log.info("event=change_set_unchanged runId={} files={} author={}", runId, fileCount, author);
            return Optional.empty();
        }
        return Optional.of(commitStaged(runId, root, fileCount, author, message));
    }

    /** A file with the folder it goes into, relative to the root of the workspace. */
    private record Placement(String folder, FileChange file) {
        String path() {
            return folder + "/" + file.path();
        }
    }

    /** Checks every path, then writes the files and adds them to the index of the run's worktree. */
    private Path stage(UUID runId, ChangeSet changes, ChangeSet evidence) {
        Path root = create(runId);
        // A worktree that was added but not checked out has an empty index, and a commit then deletes every file of the
        // branch. The one-line summary keeps the output small however many files the branch has.
        String deleted = git(root, Map.of(), "diff", "--cached", "--shortstat", "--diff-filter=D");
        if (!deleted.isEmpty()) {
            log.error("event=workspace_not_checked_out runId={} deleted=\"{}\"", runId, deleted);
            throw new IllegalStateException("The workspace is not checked out: its index lacks files of its branch, "
                    + "so a commit would delete them (" + deleted + ")");
        }
        List<Placement> placements = new ArrayList<>();
        changes.files().forEach(file -> placements.add(new Placement(product, file)));
        evidence.files().forEach(file -> placements.add(new Placement(EVIDENCE_FOLDER + "/" + runId, file)));
        for (int index = 0; index < placements.size(); index++) {
            Placement placement = placements.get(index);
            String problem = ChangeSet.pathProblem(placement.file().path());
            if (problem == null && goesThroughLink(root, placement.path())) {
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
        for (Placement placement : placements) {
            write(root.resolve(placement.path()), placement.file().content());
            add.add(placement.path());
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
        return GitCommands.run(directory, environment, arguments);
    }
}
