package io.github.bereket1144.agentcontroller.workspace;

import static io.github.bereket1144.agentcontroller.workspace.PlatformRepository.git;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses the real git command line, a temporary platform repository with one commit and a temporary base folder. */
class RunWorkspaceTest {

    private static final String PRODUCT = "apps/demo";

    @TempDir
    Path temp;

    private final UUID runId = UUID.randomUUID();
    private Path platform;
    private Path base;
    private RunWorkspace workspace;

    @BeforeEach
    void setUp() {
        platform = PlatformRepository.create(temp.resolve("platform"), Map.of(
                "README.md", "platform\n", "agent-controller/pom.xml", "<project/>\n", PRODUCT + "/old.txt", "old\n"));
        base = temp.resolve("workspaces");
        workspace = new RunWorkspace(base.toString(), platform.toString(), "HEAD", PRODUCT);
    }

    @Test
    @DisplayName("AC-AGT-67.1 a new workspace is a git worktree in <base>/<run-id> on the new branch run/<run-id> cut "
            + "from the base ref, and only the product folder is checked out")
    void createsASparseWorktreeOnTheRunBranch() throws Exception {
        Path root = workspace.create(runId);

        assertThat(root).isEqualTo(base.resolve(runId.toString()));
        // A worktree has a .git file that points at the platform repository; it has no repository of its own.
        assertThat(root.resolve(".git")).isRegularFile();
        assertThat(git(root, "symbolic-ref", "--short", "HEAD")).isEqualTo("run/" + runId);
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(git(platform, "rev-parse", "HEAD"));
        assertThat(namesIn(root)).containsExactlyInAnyOrder(".git", "apps");
        assertThat(root.resolve(PRODUCT).resolve("old.txt")).hasContent("old\n");
    }

    @Test
    @DisplayName("AC-AGT-67.1 creating the workspace again for the same run reuses it")
    void reusesTheWorkspace() throws Exception {
        Path first = workspace.create(runId);
        Files.writeString(first.resolve("marker.txt"), "kept");

        assertThat(workspace.create(runId)).isEqualTo(first);
        assertThat(first.resolve("marker.txt")).hasContent("kept");
    }

    @Test
    @DisplayName("AC-AGT-67.1 the first commit only adds the files of the change set: nothing of the branch is deleted")
    void theFirstCommitOnlyAddsFiles() {
        workspace.commit(runId, new ChangeSet(List.of(
                new FileChange("app.py", "print(1)\n"),
                new FileChange("tests/test_app.py", "def test_x():\n    pass\n"))));

        Path root = base.resolve(runId.toString());
        assertThat(git(root, "diff", "--name-status", "HEAD~1", "HEAD").lines())
                .containsExactlyInAnyOrder("A\t" + PRODUCT + "/app.py", "A\t" + PRODUCT + "/tests/test_app.py");
        assertThat(git(root, "ls-tree", "-r", "--name-only", "HEAD").lines())
                .contains("README.md", "agent-controller/pom.xml", PRODUCT + "/old.txt");
    }

    @Test
    @DisplayName("AC-AGT-67.2, AC-AGT-67.3 the files of the change set are written at their flat paths under the "
            + "product folder and committed once on the run's branch by the development role")
    void writesTheFilesUnderTheProductFolderAndCommitsThem() {
        String commitId = workspace.commit(runId, new ChangeSet(List.of(
                new FileChange("app.py", "print(1)\n"),
                new FileChange("tests/test_app.py", "def test_x():\n    pass\n"))));

        Path root = base.resolve(runId.toString());
        assertThat(workspace.productFolder(runId)).isEqualTo(root.resolve(PRODUCT));
        assertThat(root.resolve(PRODUCT).resolve("app.py")).hasContent("print(1)\n");
        assertThat(root.resolve(PRODUCT).resolve("tests/test_app.py")).hasContent("def test_x():\n    pass\n");
        assertThat(root.resolve("app.py")).doesNotExist();
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(commitId);
        assertThat(git(platform, "rev-parse", "run/" + runId)).isEqualTo(commitId);
        assertThat(workspace.head(runId)).isEqualTo(commitId);
        assertThat(git(root, "rev-list", "--count", "HEAD")).isEqualTo("2");
        assertThat(git(root, "log", "-1", "--format=%an <%ae>")).isEqualTo("development <development@agent.invalid>");
        assertThat(git(root, "log", "-1", "--format=%B")).contains(runId.toString());
    }

    @Test
    @DisplayName("AC-AGT-67.5 creating workspaces and committing leave the platform repository's branch, index and "
            + "working files as they were, and two runs get two worktrees")
    void theCheckedOutRepositoryIsNotChangedAndTwoRunsGetTwoWorktrees() throws Exception {
        String head = git(platform, "rev-parse", "HEAD");
        UUID other = UUID.randomUUID();

        workspace.commit(runId, new ChangeSet(List.of(new FileChange("app.py", "print(1)\n"))));
        workspace.commit(other, new ChangeSet(List.of(new FileChange("app.py", "print(2)\n"))));

        assertThat(git(platform, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(git(platform, "symbolic-ref", "--short", "HEAD")).isEqualTo("main");
        assertThat(git(platform, "status", "--porcelain")).isEmpty();
        assertThat(platform.resolve("README.md")).hasContent("platform\n");
        assertThat(platform.resolve(PRODUCT).resolve("app.py")).doesNotExist();
        // The platform checkout and one worktree for each run.
        assertThat(git(platform, "worktree", "list", "--porcelain").lines().filter(line -> line.startsWith("worktree ")))
                .hasSize(3);
        assertThat(base.resolve(runId.toString()).resolve(PRODUCT).resolve("app.py")).hasContent("print(1)\n");
        assertThat(base.resolve(other.toString()).resolve(PRODUCT).resolve("app.py")).hasContent("print(2)\n");
        assertThat(git(platform, "for-each-ref", "--format=%(refname:short)", "refs/heads/run/").lines())
                .containsExactlyInAnyOrder("run/" + runId, "run/" + other);
    }

    @Test
    @DisplayName("AC-AGT-67.6 the workspace of a change run is cut from the base run's branch: it has the base's files "
            + "and history, a new commit stacks on top, and the base run's branch and worktree are not changed")
    void aWorkspaceWithABaseIsCutFromTheBaseBranch() {
        UUID baseRunId = UUID.randomUUID();
        String baseHead = workspace.commit(baseRunId, new ChangeSet(List.of(new FileChange("app.py", "print(1)\n"))));
        Path baseRoot = base.resolve(baseRunId.toString());

        Path root = workspace.create(runId, baseRunId);
        String next = workspace.commit(runId, new ChangeSet(List.of(
                new FileChange("app.py", "print(2)\n"), new FileChange("more.py", "print(3)\n"))));

        assertThat(root).isEqualTo(base.resolve(runId.toString()));
        assertThat(git(root, "symbolic-ref", "--short", "HEAD")).isEqualTo("run/" + runId);
        assertThat(git(root, "rev-list", "-2", "HEAD")).isEqualTo(next + "\n" + baseHead);
        assertThat(root.resolve(PRODUCT).resolve("app.py")).hasContent("print(2)\n");
        // The base run's branch and worktree are as they were: same commit, same file, nothing new, nothing modified.
        assertThat(git(platform, "rev-parse", "run/" + baseRunId)).isEqualTo(baseHead);
        assertThat(baseRoot.resolve(PRODUCT).resolve("app.py")).hasContent("print(1)\n");
        assertThat(baseRoot.resolve(PRODUCT).resolve("more.py")).doesNotExist();
        assertThat(git(baseRoot, "status", "--porcelain")).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-67.6 when the base run's branch does not exist the call fails and nothing is created")
    void aMissingBaseBranchFailsAndCreatesNothing() {
        assertThatThrownBy(() -> workspace.create(runId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The branch of the base run does not exist");

        assertThat(base).doesNotExist();
        assertThat(git(platform, "for-each-ref", "--format=%(refname)", "refs/heads/")).isEqualTo("refs/heads/main");
    }

    @Test
    @DisplayName("AC-AGT-67.1 a repository that is not the root of a git repository, or a product folder that leaves "
            + "the repository, stops the start")
    void refusesSettingsThatCannotWork() {
        assertThatThrownBy(() -> new RunWorkspace(base.toString(), temp.toString(), "HEAD", PRODUCT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace.repository is not the root of a git repository");
        assertThatThrownBy(() -> new RunWorkspace(base.toString(), platform.toString(), "HEAD", "../elsewhere"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace.product-folder");
    }

    @Test
    @DisplayName("AC-AGT-62.7 nothing is written outside the base directory and the platform repository")
    void writesNothingOutsideTheBaseDirectory() throws Exception {
        workspace.commit(runId, new ChangeSet(List.of(new FileChange("app.py", "print(1)\n"))));

        assertThat(namesIn(temp)).containsExactlyInAnyOrder("platform", "workspaces");
        assertThat(namesIn(base)).containsExactly(runId.toString());
    }

    @Test
    @DisplayName("AC-AGT-67.2 a path that goes through a link is refused and nothing is written")
    void refusesAPathThroughALink() throws Exception {
        Path root = workspace.create(runId);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path source = Files.createDirectories(root.resolve(PRODUCT).resolve("src"));
        Files.createSymbolicLink(source.resolve("linked"), outside);
        ChangeSet changes = new ChangeSet(List.of(
                new FileChange("src/ok.py", "x"),
                new FileChange("src/linked/escape.txt", "x")));

        assertThatThrownBy(() -> workspace.commit(runId, changes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Change 2 goes through a link");
        assertThat(outside.resolve("escape.txt")).doesNotExist();
        assertThat(source.resolve("ok.py")).doesNotExist();
    }

    @Test
    @DisplayName("AC-AGT-67.2 a path under .git is refused and nothing is written")
    void refusesAPathUnderGit() {
        ChangeSet changes = new ChangeSet(List.of(
                new FileChange("app.py", "x"), new FileChange(".git/hooks/pre-commit", "x")));

        assertThatThrownBy(() -> workspace.commit(runId, changes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Change 2 has a path under .git");
        Path product = base.resolve(runId.toString()).resolve(PRODUCT);
        assertThat(product.resolve("app.py")).doesNotExist();
        assertThat(product.resolve(".git/hooks/pre-commit")).doesNotExist();
    }

    @Test
    @DisplayName("AC-AGT-67.2 a .gitignore in the change set cannot keep another file of the same change set out of the commit")
    void aGitignoreInTheChangeSetDoesNotHideAFile() {
        workspace.commit(runId, new ChangeSet(List.of(
                new FileChange(".gitignore", "ignored.txt\n"), new FileChange("ignored.txt", "x"))));

        assertThat(git(base.resolve(runId.toString()), "ls-files", "--", PRODUCT).lines())
                .containsExactlyInAnyOrder(PRODUCT + "/.gitignore", PRODUCT + "/ignored.txt", PRODUCT + "/old.txt");
    }

    private static List<String> namesIn(Path folder) throws Exception {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(entry -> entry.getFileName().toString()).toList();
        }
    }
}
