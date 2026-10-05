package io.github.bereket1144.agentcontroller.run;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.run.RunResponse.ArtifactSummary;
import io.github.bereket1144.agentcontroller.run.RunResponse.StageState;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.workflow.StageProgress;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** What a client may read about a run besides its status: stages, artifacts and the result of the build. */
@Service
public class RunDetails {

    private final StageProgress progress;
    private final ArtifactStore artifacts;
    private final VerificationRecords verifications;

    public RunDetails(StageProgress progress, ArtifactStore artifacts, VerificationRecords verifications) {
        this.progress = progress;
        this.artifacts = artifacts;
        this.verifications = verifications;
    }

    /** Every stage in the order of the graph; one that has not started says so. */
    public List<StageState> stages(UUID runId) {
        Map<String, String> states = progress.forRun(runId);
        return StageProgress.STAGES.stream()
                .map(stage -> new StageState(stage, states.getOrDefault(stage, "NOT_STARTED")))
                .toList();
    }

    /** Kind, version and summary only; the content has its own operation. */
    public List<ArtifactSummary> artifacts(UUID runId) {
        return artifacts.forRun(runId).stream()
                .map(artifact -> new ArtifactSummary(artifact.kind(), artifact.version(), artifact.summary()))
                .toList();
    }

    public Optional<VerificationResult> verification(UUID runId) {
        return verifications.latest(runId);
    }

    /** The stored artifact, or a 404 that says only that it was not found. */
    public StoredArtifact artifact(UUID runId, String kind, int version) {
        return artifacts.find(runId, kind, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found."));
    }
}
