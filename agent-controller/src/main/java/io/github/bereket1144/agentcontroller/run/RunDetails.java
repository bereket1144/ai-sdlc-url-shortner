package io.github.bereket1144.agentcontroller.run;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.run.LineageResponse.LineageArtifact;
import io.github.bereket1144.agentcontroller.run.LineageResponse.LineageFile;
import io.github.bereket1144.agentcontroller.run.RunResponse.ArtifactSummary;
import io.github.bereket1144.agentcontroller.run.RunResponse.StageState;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.workflow.StageProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** What a client may read about a run besides its status: stages, artifacts, the result of the build and the lineage. */
@Service
public class RunDetails {

    private static final Logger log = LoggerFactory.getLogger(RunDetails.class);

    /** The kind of the artifact the development role stores. */
    private static final String DEVELOPMENT = "development";

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

    /**
     * Why the run's output exists: each stored artifact with the versions it consumed, the commit and the outcome of
     * the build when there is one, and the files of the latest development artifact with the criteria it says they
     * serve. It reads stored artifacts and the stored verification result only; empty lists when nothing is stored.
     */
    public LineageResponse lineage(UUID runId) {
        List<StoredArtifact> stored = artifacts.forRun(runId);
        List<LineageArtifact> items = stored.stream()
                .map(artifact -> new LineageArtifact(artifact.kind(), artifact.version(), artifact.fingerprint(),
                        artifact.summary(), artifact.consumed()))
                .toList();
        Optional<VerificationResult> result = verifications.latest(runId);
        List<LineageFile> files = filesOfLatestDevelopment(stored);
        log.info("event=lineage_read runId={} artifacts={} files={}", runId, items.size(), files.size());
        return new LineageResponse(items, result.map(VerificationResult::commitId).orElse(null),
                result.map(latest -> latest.outcome().name()).orElse(null), files);
    }

    /** The development artifact states its criteria for the whole change, so every file carries the same list. */
    private static List<LineageFile> filesOfLatestDevelopment(List<StoredArtifact> stored) {
        return stored.stream()
                .filter(artifact -> artifact.kind().equals(DEVELOPMENT))
                .max(Comparator.comparingInt(StoredArtifact::version))
                .map(artifact -> {
                    List<String> criteria = artifact.content().path("affectedCriteria").valueStream()
                            .map(JsonNode::asString).toList();
                    return artifact.content().path("changes").valueStream()
                            .map(change -> new LineageFile(change.path("path").asString(), criteria)).toList();
                })
                .orElse(List.of());
    }

    /** The stored artifact, or a 404 that says only that it was not found. */
    public StoredArtifact artifact(UUID runId, String kind, int version) {
        return artifacts.find(runId, kind, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found."));
    }
}
