package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.sandbox.VerificationFailedException;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRunner;
import io.github.bereket1144.agentcontroller.workspace.RunWorkspace;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceFiles;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DevelopmentFinishTest {

    private final UUID runId = UUID.randomUUID();
    private final ArtifactRef ref = new ArtifactRef("development", 1, "f".repeat(64));
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final RunWorkspace workspaces = mock(RunWorkspace.class);
    private final VerificationRunner verifier = mock(VerificationRunner.class);
    private final VerificationRecords records = mock(VerificationRecords.class);
    private final DevelopmentFinish finish = new DevelopmentFinish(artifacts, workspaces, verifier, records,
            new WorkspaceFiles());
    private final AgentState state = new AgentState(Map.of("runId", runId));
    private final Path product = Path.of("workspace", "apps", "demo");

    private void givenAnArtifactAndACommit() {
        when(artifacts.find(runId, "development", 1)).thenReturn(Optional.of(new StoredArtifact(runId, "development",
                1, JsonMapper.shared().readTree(
                "{\"changes\":[{\"path\":\"app.py\",\"content\":\"print(1)\"}]}"),
                "changes 1", ref.fingerprint(), "development", "1", "local", "m", List.of())));
        when(workspaces.productFolder(runId)).thenReturn(product);
        when(workspaces.commitIfChanged(any(), any(), any(), any())).thenReturn(Optional.of("abc123"));
    }

    @Test
    @DisplayName("AC-AGT-62.10, AC-AGT-67.4 a passing build and test of the product folder leaves the commit and the "
            + "outcome for the next stages")
    void aPassingResultGoesOn() {
        givenAnArtifactAndACommit();
        VerificationResult passed = new VerificationResult(Outcome.PASSED, 2, 2, 0, List.of(), "", "abc123");
        when(verifier.verify(product, "abc123")).thenReturn(passed);

        Map<String, Object> update = finish.finish(state, Map.of("development", ref));

        assertThat(update).containsEntry("development", ref).containsEntry("commit", "abc123")
                .containsEntry("verification", "PASSED");
        // The sandbox is given the product folder, not the root of the workspace.
        verify(verifier).verify(product, "abc123");
        verify(records).record(runId, passed);
    }

    @Test
    @DisplayName("AC-AGT-62.10 a failed or missing result is stored and ends the stage with VERIFICATION_FAILED")
    void aFailedResultEndsTheStage() {
        givenAnArtifactAndACommit();
        VerificationResult noResult = VerificationResult.noResult("abc123");
        when(verifier.verify(product, "abc123")).thenReturn(noResult);

        assertThatThrownBy(() -> finish.finish(state, Map.of("development", ref)))
                .isInstanceOf(VerificationFailedException.class);
        verify(records).record(runId, noResult);
    }

    @Test
    @DisplayName("AC-AGT-65.3 a fix attempt that changes no file is checked again on the commit the branch is on, "
            + "and fails again instead of failing on an empty commit")
    void anUnchangedFixAttemptFailsAgain() {
        givenAnArtifactAndACommit();
        when(workspaces.commitIfChanged(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(workspaces.head(runId)).thenReturn("abc123");
        VerificationResult failed = new VerificationResult(Outcome.FAILED, 1, 0, 1, List.of("test_x"), "", "abc123");
        when(verifier.verify(product, "abc123")).thenReturn(failed);

        assertThatThrownBy(() -> finish.finish(state, Map.of("development", ref)))
                .isInstanceOf(VerificationFailedException.class);
        verify(records).record(runId, failed);
    }

    @Test
    @DisplayName("AC-AGT-66.3 the first try of a restarted attempt writes its code afresh: the check that failed "
            + "before the restart is not a reason for a fix attempt")
    void aRestartedAttemptIsNotAFixAttempt() {
        givenAnArtifactAndACommit();
        VerificationResult before = new VerificationResult(Outcome.FAILED, 1, 0, 1, List.of("test_x"), "", "old");
        when(records.latest(runId)).thenReturn(Optional.of(before));
        when(verifier.verify(product, "abc123"))
                .thenReturn(new VerificationResult(Outcome.PASSED, 2, 2, 0, List.of(), "", "abc123"));
        AgentStageNode node = mock(AgentStageNode.class);
        AgentState restarted = new AgentState(Map.of("runId", runId, DevelopmentFinish.PREVIOUS_ATTEMPT_COMMIT, "old"));
        when(node.execute(restarted)).thenReturn(Map.of("development", ref));

        Map<String, Object> update = finish.develop(restarted, node);

        assertThat(update).containsEntry("commit", "abc123");
        verify(node).execute(restarted);
        verify(node, never()).execute(any(), anyString(), anyList());
    }
}
