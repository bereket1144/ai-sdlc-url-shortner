package io.github.bereket1144.agentcontroller.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.run.RunKind;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * What a retried attempt takes over from the attempts before it. {@link #startPoint} says where the first retry starts,
 * and {@link #input} rebuilds the graph input of an attempt from what is stored, so that the stages before its start
 * point are not run again.
 */
@Service
public class RunResume {

    private static final Logger log = LoggerFactory.getLogger(RunResume.class);

    /** The start point of an attempt that runs the whole graph again, from the requirements stage. */
    public static final String START = "START";

    /** The stages that run side by side after development. A retry starts them all together, as one group. */
    private static final List<String> GROUP = List.of("qa", "review", "documentation");
    /** The stages a retry can start at, besides START. */
    private static final List<String> RESUMABLE = List.of("impactAnalysis", "design", "planning", "development",
            "qa", "review", "documentation");
    private static final String FINISHED = "FINISHED";

    private final ArtifactStore artifacts;
    private final RoleCatalog roles;
    private final VerificationRecords verifications;

    public RunResume(ArtifactStore artifacts, RoleCatalog roles, VerificationRecords verifications) {
        this.artifacts = artifacts;
        this.roles = roles;
        this.verifications = verifications;
    }

    /**
     * Where the first retry of a failed run starts: the earliest stage, in graph order, that is not FINISHED. Impact
     * analysis is only a stage of a brownfield run. When the requirements stage is not finished the attempt is the whole
     * graph again, {@link #START}. When every stage is finished the run failed after the join, and the group is run again.
     *
     * @param states the stored state of each stage that has one; a stage that is absent has not started
     */
    public static String startPoint(Map<String, String> states, RunKind kind) {
        for (String stage : StageProgress.STAGES) {
            if (stage.equals("impactAnalysis") && kind != RunKind.BROWNFIELD) {
                continue;
            }
            if (!FINISHED.equals(states.get(stage))) {
                return stage.equals("requirements") ? START : stage;
            }
        }
        return GROUP.get(0);
    }

    /**
     * The graph input of an attempt that starts at {@code startAt}. From {@link #START} it is the run's own inputs,
     * and the commit of the latest check, so that development knows what the restart leaves behind. From a stage it also
     * holds what the stages before that point left: the latest artifact of each, or the marker of a stage that ran on
     * its dummy node and stored none. The group starts after development, with the commit and the outcome of its check.
     *
     * @throws IllegalArgumentException when {@code startAt} is neither START nor a stage a retry can start at
     */
    Map<String, Object> input(UUID runId, String requirement, RunKind kind, String startAt) {
        Map<String, Object> input = new HashMap<>(Map.of("runId", runId, "requirement", requirement, "kind", kind));
        if (START.equals(startAt)) {
            verifications.latest(runId).ifPresent(checked ->
                    input.put(DevelopmentFinish.PREVIOUS_ATTEMPT_COMMIT, checked.commitId()));
            return input;
        }
        if (!RESUMABLE.contains(startAt)) {
            throw new IllegalArgumentException("A retry cannot start at " + startAt);
        }
        int first = StageProgress.STAGES.indexOf(GROUP.contains(startAt) ? GROUP.get(0) : startAt);
        Map<String, ArtifactRef> latest = latestRefs(runId);
        List<String> restored = new ArrayList<>();
        for (String stage : StageProgress.STAGES.subList(0, first)) {
            if (stage.equals("impactAnalysis") && kind != RunKind.BROWNFIELD) {
                continue;
            }
            ArtifactRef ref = latest.get(roles.get(stage).output().kind());
            input.put(stage, ref != null ? ref : stage + ":done");
            restored.add(stage);
        }
        if (first > StageProgress.STAGES.indexOf("development")) {
            verifications.latest(runId).ifPresent(result -> {
                input.put(DevelopmentFinish.COMMIT, result.commitId());
                input.put(DevelopmentFinish.VERIFICATION, result.outcome().name());
            });
        }
        // Ids, stage names and counts only: no artifact content and no requirement text.
        log.info("event=resume_state_rebuilt runId={} startAt={} restored={}", runId, startAt, restored);
        return input;
    }

    private Map<String, ArtifactRef> latestRefs(UUID runId) {
        Map<String, ArtifactRef> latest = new HashMap<>();
        for (StoredArtifact artifact : artifacts.forRun(runId)) {
            latest.merge(artifact.kind(), new ArtifactRef(artifact.kind(), artifact.version(), artifact.fingerprint()),
                    (kept, other) -> other.version() > kept.version() ? other : kept);
        }
        return latest;
    }
}
