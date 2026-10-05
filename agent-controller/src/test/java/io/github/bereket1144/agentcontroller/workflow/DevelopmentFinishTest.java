package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    private final DevelopmentFinish finish = new DevelopmentFinish(artifacts, workspaces, verifier, records);
    private final AgentState state = new AgentState(Map.of("runId", runId));
    private final Path workspace = Path.of("workspace");

    private void givenAnArtifactAndACommit() {
        when(artifacts.find(runId, "development", 1)).thenReturn(Optional.of(new StoredArtifact(runId, "development",
                1, JsonMapper.shared().readTree(
                "{\"changes\":[{\"path\":\"apps/url-shortener/app.py\",\"content\":\"print(1)\"}]}"),
                "changes 1", ref.fingerprint(), "development", "1", "local", "m", List.of())));
        when(workspaces.create(runId)).thenReturn(workspace);
        when(workspaces.commit(any(), any())).thenReturn("abc123");
    }

    @Test
    @DisplayName("AC-AGT-62.10 a passing build and test leaves the commit and the outcome for the next stages")
    void aPassingResultGoesOn() {
        givenAnArtifactAndACommit();
        VerificationResult passed = new VerificationResult(Outcome.PASSED, 2, 2, 0, List.of(), "", "abc123");
        when(verifier.verify(workspace, "abc123")).thenReturn(passed);

        Map<String, Object> update = finish.finish(state, Map.of("development", ref));

        assertThat(update).containsEntry("development", ref).containsEntry("commit", "abc123")
                .containsEntry("verification", "PASSED");
        verify(records).record(runId, passed);
    }

    @Test
    @DisplayName("AC-AGT-62.10 a failed or missing result is stored and ends the stage with VERIFICATION_FAILED")
    void aFailedResultEndsTheStage() {
        givenAnArtifactAndACommit();
        VerificationResult noResult = VerificationResult.noResult("abc123");
        when(verifier.verify(workspace, "abc123")).thenReturn(noResult);

        assertThatThrownBy(() -> finish.finish(state, Map.of("development", ref)))
                .isInstanceOf(VerificationFailedException.class);
        verify(records).record(runId, noResult);
    }
}
