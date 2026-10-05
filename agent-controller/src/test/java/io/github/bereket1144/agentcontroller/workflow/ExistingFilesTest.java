package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.run.RunKind;
import io.github.bereket1144.agentcontroller.workspace.ChangeSet;
import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import io.github.bereket1144.agentcontroller.workspace.RunWorkspace;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceFiles;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real workspace and the real git command line, in a temporary base folder. */
class ExistingFilesTest {

    @TempDir
    Path temp;

    private final UUID baseRunId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private RunWorkspace workspaces;
    private ExistingFiles existing;

    @BeforeEach
    void setUp() {
        workspaces = new RunWorkspace(temp.resolve("workspaces").toString());
        existing = new ExistingFiles(workspaces, new WorkspaceFiles());
    }

    @Test
    @DisplayName("AC-AGT-49.1 a BROWNFIELD run is given the files of the clone of its base run's workspace, as an "
            + "optional input versioned by the commit they are at")
    void aBrownfieldRunGetsTheFilesOfTheBase() {
        String baseHead = workspaces.commit(baseRunId, new ChangeSet(List.of(
                new FileChange("app.py", "print(1)\n"), new FileChange("tests/test_app.py", "def test_x(): pass\n"))));

        Optional<PromptInput> input = existing.forRun(new AgentState(Map.of(
                "runId", runId, "requirement", "r", "kind", RunKind.BROWNFIELD, "baseRunId", baseRunId)));

        assertThat(input).hasValueSatisfying(files -> {
            assertThat(files.kind()).isEqualTo(InputKind.SELECTED_WORKSPACE_FILES);
            assertThat(files.version()).isEqualTo(baseHead);
            assertThat(files.optional()).isTrue();
            assertThat(files.fullText()).contains("[FILE path=app.py]\nprint(1)\n\n[/FILE]",
                    "[FILE path=tests/test_app.py]");
            assertThat(files.shortText()).contains("Files: app.py, tests/test_app.py");
        });
        assertThat(workspaces.head(runId)).isEqualTo(baseHead);
    }

    @Test
    @DisplayName("AC-AGT-49.1 a GREENFIELD run is given no files and no workspace is made for it")
    void aGreenfieldRunGetsNoFiles() {
        Optional<PromptInput> input = existing.forRun(new AgentState(Map.of(
                "runId", runId, "requirement", "r", "kind", RunKind.GREENFIELD)));

        assertThat(input).isEmpty();
        assertThat(temp.resolve("workspaces")).doesNotExist();
    }

    @Test
    @DisplayName("AC-AGT-50.5 a BROWNFIELD run without a base run id in its state fails at once, naming the missing key")
    void aBrownfieldRunWithoutABaseFails() {
        AgentState state = new AgentState(Map.of("runId", runId, "requirement", "r", "kind", RunKind.BROWNFIELD));

        assertThatThrownBy(() -> existing.forRun(state)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing workflow state: baseRunId");
    }
}
