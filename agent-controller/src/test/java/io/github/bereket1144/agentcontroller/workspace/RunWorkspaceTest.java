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

/** Uses the real git command line, a temporary source repository and a temporary base folder. */
class RunWorkspaceTest {

    @TempDir
    Path temp;

    private final UUID runId = UUID.randomUUID();
    private Path source;
    private Path base;
    private RunWorkspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        source = Files.createDirectory(temp.resolve("source"));
        base = temp.resolve("workspaces");
        git(source, "init", "--quiet", "-b", "main");
        Files.writeString(source.resolve("README.md"), "source");
        git(source, "add", "README.md");
        git(source, "commit", "--quiet", "-m", "first");
        workspace = new RunWorkspace(base.toString(), source.toString());
    }

    @Test
    @DisplayName("AC-AGT-62.7 the files are written to <base>/<run-id> and committed once on run/<run-id> by the development role")
    void writesAndCommitsTheChangeSet() throws Exception {
        String commitId = workspace.commit(runId, new ChangeSet(List.of(
                new FileChange("apps/url-shortener/app.py", "print(1)\n"),
                new FileChange("runs/" + runId + "/notes.md", "notes"))));

        Path root = base.resolve(runId.toString());
        assertThat(Files.readString(root.resolve("apps/url-shortener/app.py"))).isEqualTo("print(1)\n");
        assertThat(Files.readString(root.resolve("runs/" + runId + "/notes.md"))).isEqualTo("notes");
        assertThat(git(root, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("run/" + runId);
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(commitId);
        assertThat(git(root, "rev-list", "--count", "origin/main..HEAD")).isEqualTo("1");
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
    @DisplayName("AC-AGT-62.7 only the product folder and the runs folder are checked out in the workspace")
    void checksOutOnlyTheProductAndRunsFolders() throws Exception {
        Path root = workspace.create(UUID.randomUUID());

        // The source holds README.md; it is in the clone's history but not in its working folder.
        assertThat(root.resolve("README.md")).doesNotExist();
        workspace.commit(UUID.fromString(root.getFileName().toString()), new ChangeSet(List.of(
                new FileChange("apps/url-shortener/app.py", "x"))));
        assertThat(git(root, "ls-tree", "-r", "--name-only", "HEAD")).contains("README.md", "apps/url-shortener/app.py");
    }

    @Test
    @DisplayName("AC-AGT-62.7 nothing is written outside the base directory and the source checkout is unchanged")
    void leavesEverythingElseAlone() throws Exception {
        String headBefore = git(source, "rev-parse", "HEAD");

        workspace.commit(runId, new ChangeSet(List.of(new FileChange("apps/url-shortener/app.py", "print(1)\n"))));

        assertThat(git(source, "rev-parse", "HEAD")).isEqualTo(headBefore);
        assertThat(git(source, "branch", "--show-current")).isEqualTo("main");
        assertThat(git(source, "branch", "--list", "run/*")).isEmpty();
        assertThat(git(source, "status", "--porcelain")).isEmpty();
        assertThat(namesIn(temp)).containsExactlyInAnyOrder("source", "workspaces");
        assertThat(namesIn(base)).containsExactly(runId.toString());
    }

    @Test
    @DisplayName("AC-AGT-62.6 a path that goes through a link is refused and nothing is written")
    void refusesAPathThroughALink() throws Exception {
        Path root = workspace.create(runId);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.createDirectories(root.resolve("apps/url-shortener"));
        Files.createSymbolicLink(root.resolve("apps/url-shortener/linked"), outside);
        ChangeSet changes = new ChangeSet(List.of(
                new FileChange("apps/url-shortener/ok.py", "x"),
                new FileChange("apps/url-shortener/linked/escape.txt", "x")));

        assertThatThrownBy(() -> workspace.commit(runId, changes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Change 2 goes through a link");
        assertThat(outside.resolve("escape.txt")).doesNotExist();
        assertThat(root.resolve("apps/url-shortener/ok.py")).doesNotExist();
    }

    @Test
    @DisplayName("AC-AGT-62.6 a path in the folder of another run is refused")
    void refusesTheFolderOfAnotherRun() {
        ChangeSet changes = new ChangeSet(List.of(new FileChange("runs/" + UUID.randomUUID() + "/x.md", "x")));

        assertThatThrownBy(() -> workspace.commit(runId, changes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Change 1 has a path outside apps/url-shortener/ and runs/<run-id>/");
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
