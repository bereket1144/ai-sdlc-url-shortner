package io.github.bereket1144.agentcontroller.workspace;

import static io.github.bereket1144.agentcontroller.workspace.PlatformRepository.git;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real Git repositories in temporary folders; these tests never merge into the developer's repository. */
class WorkspaceMergeTest {

    private static final String PRODUCT = "apps/demo";

    @TempDir Path temp;
    private final UUID runId = UUID.randomUUID();
    private Path platform;
    private RunWorkspace workspaces;
    private WorkspaceMerge merge;

    @BeforeEach
    void setUp() {
        platform = PlatformRepository.create(temp.resolve("platform"), Map.of(
                "README.md", "platform\n", PRODUCT + "/app.py", "print('original')\n"));
        workspaces = new RunWorkspace(temp.resolve("workspaces").toString(), platform.toString(), "HEAD", PRODUCT);
        merge = new WorkspaceMerge(workspaces);
    }

    @Test
    @DisplayName("AC-AGT-68.1, 68.2, 68.4 delivery brings files and evidence into the checkout, preserves both "
            + "histories and unrelated platform commits, and is idempotent")
    void mergesCompletedWorkWithItsHistory() throws Exception {
        String development = generate();
        String exported = workspaces.commitIfChanged(runId,
                new ChangeSet(List.of(new FileChange("docs/design.md", "# Design\n"))),
                new ChangeSet(List.of(new FileChange("lineage.json", "{}\n"))), "controller", "Export documents")
                .orElseThrow();
        Files.writeString(platform.resolve("README.md"), "updated platform\n");
        git(platform, "add", "README.md");
        git(platform, "commit", "--quiet", "-m", "Update the platform");
        String checkoutHead = git(platform, "rev-parse", "HEAD");

        String merged = merge.merge(runId);

        assertThat(platform.resolve(PRODUCT + "/app.py")).hasContent("print('generated')\n");
        assertThat(platform.resolve(PRODUCT + "/docs/design.md")).hasContent("# Design\n");
        assertThat(platform.resolve("runs/" + runId + "/lineage.json")).hasContent("{}\n");
        assertThat(platform.resolve("README.md")).hasContent("updated platform\n");
        assertThat(git(platform, "symbolic-ref", "--short", "HEAD")).isEqualTo("main");
        assertThat(git(platform, "log", "-1", "--format=%P")).isEqualTo(checkoutHead + " " + exported);
        assertThat(git(platform, "show", "-s", "--format=%an", development)).isEqualTo("development");
        assertThat(git(platform, "log", "-1", "--format=%an <%ae>")).isEqualTo("controller <controller@agent.invalid>");
        assertThat(git(platform, "log", "-1", "--format=%B")).contains(runId.toString());
        assertThat(git(platform, "status", "--porcelain")).isEmpty();
        assertThat(workspaces.head(runId)).isEqualTo(exported);
        assertThat(merge.merge(runId)).isEqualTo(merged);
        assertThat(git(platform, "rev-parse", "HEAD")).isEqualTo(merged);
    }

    @Test
    @DisplayName("AC-AGT-68.3 local tracked, staged and untracked work is retained when delivery is blocked")
    void leavesLocalEditsAlone() throws Exception {
        generate();
        Files.writeString(platform.resolve("README.md"), "my staged edit\n");
        git(platform, "add", "README.md");
        Files.writeString(platform.resolve(PRODUCT + "/app.py"), "print('my edit')\n");
        Files.writeString(platform.resolve("notes.txt"), "untracked\n");
        String before = git(platform, "rev-parse", "HEAD");
        String status = git(platform, "status", "--porcelain");

        assertThatThrownBy(() -> merge.merge(runId)).isInstanceOf(DeliveryBlockedException.class)
                .hasMessage("DIRTY_CHECKOUT");

        assertThat(git(platform, "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(git(platform, "status", "--porcelain")).isEqualTo(status);
        assertThat(platform.resolve("README.md")).hasContent("my staged edit\n");
        assertThat(platform.resolve(PRODUCT + "/app.py")).hasContent("print('my edit')\n");
        assertThat(platform.resolve("notes.txt")).hasContent("untracked\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DETACHED_HEAD", "GIT_OPERATION_IN_PROGRESS"})
    @DisplayName("AC-AGT-68.3 a detached checkout or existing Git operation is not changed")
    void refusesACheckoutThatIsNotReady(String reason) throws Exception {
        String source = generate();
        Path marker = platform.resolve(".git/MERGE_HEAD");
        if (reason.equals("DETACHED_HEAD")) {
            git(platform, "checkout", "--quiet", "--detach");
        } else {
            Files.writeString(marker, source + "\n");
        }
        String before = git(platform, "rev-parse", "HEAD");

        assertThatThrownBy(() -> merge.merge(runId)).isInstanceOf(DeliveryBlockedException.class).hasMessage(reason);

        assertThat(git(platform, "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(platform.resolve(PRODUCT + "/app.py")).hasContent("print('original')\n");
        if (reason.equals("GIT_OPERATION_IN_PROGRESS")) {
            assertThat(marker).hasContent(source + "\n");
        }
    }

    @Test
    @DisplayName("AC-AGT-68.4 a merge conflict is reported before changing the checkout")
    void refusesAConflictWithoutStartingAMerge() throws Exception {
        String source = generate();
        Files.writeString(platform.resolve(PRODUCT + "/app.py"), "print('other accepted work')\n");
        git(platform, "add", PRODUCT);
        git(platform, "commit", "--quiet", "-m", "Accept other work");
        String before = git(platform, "rev-parse", "HEAD");

        assertThatThrownBy(() -> merge.merge(runId)).isInstanceOf(DeliveryBlockedException.class)
                .hasMessage("MERGE_CONFLICT");

        assertThat(git(platform, "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(git(platform, "status", "--porcelain")).isEmpty();
        assertThat(platform.resolve(".git/MERGE_HEAD")).doesNotExist();
        assertThat(platform.resolve(PRODUCT + "/app.py")).hasContent("print('other accepted work')\n");
        assertThat(workspaces.head(runId)).isEqualTo(source);
    }

    @Test
    @DisplayName("AC-AGT-68.4 a run branch that changes a platform file is not delivered")
    void refusesChangesOutsideTheProductAndEvidence() throws Exception {
        generate();
        Path root = workspaces.create(runId);
        git(root, "sparse-checkout", "disable");
        Files.writeString(root.resolve("README.md"), "unexpected platform change\n");
        git(root, "add", "README.md");
        git(root, "commit", "--quiet", "-m", "Unexpected change");
        String before = git(platform, "rev-parse", "HEAD");

        assertThatThrownBy(() -> merge.merge(runId)).isInstanceOf(DeliveryBlockedException.class)
                .hasMessage("UNRELATED_CHANGES");

        assertThat(git(platform, "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(platform.resolve("README.md")).hasContent("platform\n");
    }

    private String generate() {
        return workspaces.commit(runId, new ChangeSet(List.of(new FileChange("app.py", "print('generated')\n"))));
    }
}
