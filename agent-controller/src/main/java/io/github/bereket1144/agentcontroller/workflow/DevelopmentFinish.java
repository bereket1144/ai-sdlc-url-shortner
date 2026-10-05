package io.github.bereket1144.agentcontroller.workflow;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.sandbox.VerificationFailedException;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRunner;
import io.github.bereket1144.agentcontroller.workspace.ChangeSet;
import io.github.bereket1144.agentcontroller.workspace.RunWorkspace;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * What happens to the development agent's answer: its change set is written and committed in the run's workspace, the
 * commit is built and tested in the sandbox, and the result is kept. A result that is not a clear pass ends the stage,
 * and so the run, with {@link VerificationFailedException}. It exists only when the development agent is on.
 */
@Component
@ConditionalOnProperty(name = "agent.stages.development", havingValue = "agent")
public class DevelopmentFinish {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentFinish.class);

    private final ArtifactStore artifacts;
    private final RunWorkspace workspaces;
    private final VerificationRunner verifier;
    private final VerificationRecords records;

    public DevelopmentFinish(ArtifactStore artifacts, RunWorkspace workspaces, VerificationRunner verifier,
                             VerificationRecords records) {
        this.artifacts = artifacts;
        this.workspaces = workspaces;
        this.verifier = verifier;
        this.records = records;
    }

    /**
     * @param update what the development stage returned: its artifact reference under {@code development}
     * @return the same update with the commit id and the outcome added for the stages after it
     */
    Map<String, Object> finish(AgentState state, Map<String, Object> update) {
        UUID runId = state.<UUID>value("runId").orElseThrow(() -> new IllegalStateException("Missing run id"));
        ArtifactRef ref = (ArtifactRef) update.get("development");
        StoredArtifact artifact = artifacts.find(runId, ref.kind(), ref.version())
                .orElseThrow(() -> new IllegalStateException("The development artifact was not stored"));
        ChangeSet changes = ChangeSet.from(artifact.content().get("changes"));
        Path workspace = workspaces.create(runId);
        String commit = workspaces.commit(runId, changes);
        VerificationResult result = verifier.verify(workspace, commit);
        records.record(runId, result);
        log.info("event=development_verified runId={} commit={} outcome={}", runId, commit, result.outcome());
        if (!result.passed()) {
            throw new VerificationFailedException(result.outcome());
        }
        Map<String, Object> merged = new HashMap<>(update);
        merged.put("commit", commit);
        merged.put("verification", result.outcome().name());
        return merged;
    }
}
