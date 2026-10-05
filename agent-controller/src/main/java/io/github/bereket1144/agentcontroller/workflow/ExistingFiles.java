package io.github.bereket1144.agentcontroller.workflow;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.run.RunKind;
import io.github.bereket1144.agentcontroller.workspace.RunWorkspace;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceFiles;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceFiles.FileText;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceFiles.Snapshot;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The existing code of a change run, as one prompt input. For a BROWNFIELD run it makes sure the run's workspace is a
 * clone of its base run's workspace (the first call makes the clone) and reads the bounded files of that clone; for any
 * other run it gives nothing. It is its own class because the stages that are shown the code (the stage node, and the
 * development stage with its fix attempts) need the same reading and the same wording, and the stage node does not know
 * about workspaces. It exists only when the development agent is on, because only then there is a workspace.
 */
@Component
@ConditionalOnProperty(name = "agent.stages.development", havingValue = "agent")
public class ExistingFiles {

    private static final Logger log = LoggerFactory.getLogger(ExistingFiles.class);

    private final RunWorkspace workspaces;
    private final WorkspaceFiles files;

    public ExistingFiles(RunWorkspace workspaces, WorkspaceFiles files) {
        this.workspaces = workspaces;
        this.files = files;
    }

    /**
     * @param state the graph state of the run: its kind, its id and, for a BROWNFIELD run, the id of its base run
     * @return the files as an input, or empty for a run that is not BROWNFIELD
     * @throws IllegalStateException when a BROWNFIELD run has no base run id, or its base workspace does not exist
     */
    Optional<PromptInput> forRun(AgentState state) {
        if (state.<RunKind>value("kind").filter(kind -> kind == RunKind.BROWNFIELD).isEmpty()) {
            return Optional.empty();
        }
        UUID runId = state.<UUID>value("runId")
                .orElseThrow(() -> new IllegalStateException("Missing workflow state: runId"));
        UUID baseRunId = state.<UUID>value(WorkflowRunner.BASE_RUN_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing workflow state: " + WorkflowRunner.BASE_RUN_ID));
        Path workspace = workspaces.create(runId, baseRunId);
        Snapshot snapshot = files.read(workspace);
        // Ids and counts only: the paths and the text of the code are never logged.
        log.info("event=existing_files_read runId={} baseRunId={} files={} omitted={}", runId, baseRunId,
                snapshot.files().size(), snapshot.omitted());
        return Optional.of(input(workspaces.head(runId), snapshot));
    }

    /**
     * The files as one input, in the order of their paths. The short form, used only when the prompt is too large,
     * lists the paths.
     *
     * @param commit the commit the files are at; it is the version of the input
     */
    static PromptInput input(String commit, Snapshot snapshot) {
        String notice = snapshot.omitted() == 0 ? ""
                : "[" + snapshot.omitted() + " more files are not shown: binary, too large, or over the limit]\n";
        String full = snapshot.files().stream()
                .map(file -> "[FILE path=" + file.path() + "]\n" + file.text() + "\n[/FILE]\n")
                .collect(Collectors.joining()) + notice;
        String paths = "Files: " + snapshot.files().stream().map(FileText::path).collect(Collectors.joining(", "))
                + "\n" + notice;
        return new PromptInput(InputKind.SELECTED_WORKSPACE_FILES, commit, full, paths, true);
    }
}
