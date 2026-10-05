package io.github.bereket1144.agentcontroller.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses the real git command line and a temporary base folder. */
class RunWorkspaceTest {

    @TempDir
    Path temp;

    private final UUID runId = UUID.randomUUID();
    private Path base;
    private RunWorkspace workspace;

    @BeforeEach
    void setUp() {
        base = temp.resolve("workspaces");
        workspace = new RunWorkspace(base.toString());
    }

    @Test
    @DisplayName("AC-AGT-62.7 a new workspace is an empty git repository in <base>/<run-id> on main, not a clone")
    void createsAnEmptyRepositoryOnMain() throws Exception {
        Path root = workspace.create(runId);

        assertThat(root).isEqualTo(base.resolve(runId.toString()));
        assertThat(namesIn(root)).containsExactly(".git");
        assertThat(git(root, "symbolic-ref", "--short", "HEAD")).isEqualTo("main");
        assertThat(git(root, "remote")).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-62.7 the files are written at their paths and committed once on main by the development role")
    void writesAndCommitsTheChangeSet() throws Exception {
        String commitId = workspace.commit(runId, new ChangeSet(List.of(
                new FileChange("app.py", "print(1)\n"),
                new FileChange("tests/test_app.py", "def test_x():\n    pass\n"))));

        Path root = base.resolve(runId.toString());
        assertThat(Files.readString(root.resolve("app.py"))).isEqualTo("print(1)\n");
        assertThat(Files.readString(root.resolve("tests/test_app.py"))).isEqualTo("def test_x():\n    pass\n");
        assertThat(git(root, "symbolic-ref", "--short", "HEAD")).isEqualTo("main");
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(commitId);
        assertThat(workspace.head(runId)).isEqualTo(commitId);
        assertThat(git(root, "rev-list", "--count", "HEAD")).isEqualTo("1");
        assertThat(git(root, "log", "-1", "--format=%an <%ae>")).isEqualTo("development <development@agent.invalid>");
        assertThat(git(root, "log", "-1", "--format=%B")).contains(runId.toString());
    }

    @Test
    @DisplayName("AC-AGT-62.7 creating the workspace again for the same run reuses it")
    void reusesTheWorkspace() throws Exception {
        Path first = workspace.create(runId);
        Files.writeString(first.resolve("marker.txt"), "kept");

        assertThat(workspace.create(runId)).isEqualTo(first);
        assertThat(first.resolve("marker.txt")).hasContent("kept");
    }

    @Test
    @DisplayName("AC-AGT-50.5 the workspace of a run with a base run is a clone of the base's workspace on its branch: it "
            + "has the base's files and history, a new commit stacks on top, and the base repository is not changed")
    void aWorkspaceWithABaseIsACloneOfTheBase() throws Exception {
        UUID baseRunId = UUID.randomUUID();
        String baseHead = workspace.commit(baseRunId, new ChangeSet(List.of(new FileChange("app.py", "print(1)\n"))));
        Path baseRoot = base.resolve(baseRunId.toString());

        Path root = workspace.create(runId, baseRunId);
        String next = workspace.commit(runId, new ChangeSet(List.of(
                new FileChange("app.py", "print(2)\n"), new FileChange("more.py", "print(3)\n"))));

        assertThat(root).isEqualTo(base.resolve(runId.toString()));
        assertThat(git(root, "symbolic-ref", "--short", "HEAD")).isEqualTo("main");
        assertThat(git(root, "rev-list", "main")).isEqualTo(next + "\n" + baseHead);
        assertThat(root.resolve("app.py")).hasContent("print(2)\n");
        // The base run's repository is as it was: same commit, same file, nothing new, nothing modified.
        assertThat(git(baseRoot, "rev-list", "main")).isEqualTo(baseHead);
        assertThat(baseRoot.resolve("app.py")).hasContent("print(1)\n");
        assertThat(baseRoot.resolve("more.py")).doesNotExist();
        assertThat(git(baseRoot, "status", "--porcelain")).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-50.5 when the base run's workspace does not exist the call fails and nothing is written")
    void aMissingBaseWorkspaceFailsAndWritesNothing() {
        assertThatThrownBy(() -> workspace.create(runId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The workspace of the base run does not exist");

        assertThat(base).doesNotExist();
    }

    @Test
    @DisplayName("AC-AGT-62.7 nothing is written outside the base directory")
    void writesNothingOutsideTheBaseDirectory() throws Exception {
        workspace.commit(runId, new ChangeSet(List.of(new FileChange("app.py", "print(1)\n"))));

        assertThat(namesIn(temp)).containsExactly("workspaces");
        assertThat(namesIn(base)).containsExactly(runId.toString());
    }

    @Test
    @DisplayName("AC-AGT-62.6 a path that goes through a link is refused and nothing is written")
    void refusesAPathThroughALink() throws Exception {
        Path root = workspace.create(runId);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.createDirectories(root.resolve("src"));
        Files.createSymbolicLink(root.resolve("src/linked"), outside);
        ChangeSet changes = new ChangeSet(List.of(
                new FileChange("src/ok.py", "x"),
                new FileChange("src/linked/escape.txt", "x")));

        assertThatThrownBy(() -> workspace.commit(runId, changes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Change 2 goes through a link");
        assertThat(outside.resolve("escape.txt")).doesNotExist();
        assertThat(root.resolve("src/ok.py")).doesNotExist();
    }

    @Test
    @DisplayName("AC-AGT-62.6 a path under .git is refused and nothing is written")
    void refusesAPathUnderGit() {
        ChangeSet changes = new ChangeSet(List.of(
                new FileChange("app.py", "x"), new FileChange(".git/hooks/pre-commit", "x")));

        assertThatThrownBy(() -> workspace.commit(runId, changes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Change 2 has a path under .git");
        assertThat(base.resolve(runId.toString()).resolve("app.py")).doesNotExist();
        assertThat(base.resolve(runId.toString()).resolve(".git/hooks/pre-commit")).doesNotExist();
    }

    private static List<String> namesIn(Path folder) throws Exception {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(entry -> entry.getFileName().toString()).toList();
        }
    }

    /** Runs git with a fixed identity of its own, so that a test never depends on the user's git settings. */
    private static String git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=test",
                "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }
}
