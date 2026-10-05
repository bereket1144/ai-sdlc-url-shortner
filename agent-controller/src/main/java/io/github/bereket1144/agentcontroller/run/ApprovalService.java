package io.github.bereket1144.agentcontroller.run;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.workflow.RunResume;
import io.github.bereket1144.agentcontroller.workflow.StageProgress;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Holds the result of a stage for a reviewer and applies the reviewer's decision. A stage listed in
 * {@code agent.approval.required-for} (design and planning) stops the run after it has stored its artifact: the run
 * waits, and what it waits for is read from what is stored, so it survives a restart: the latest artifact of a listed
 * stage that is finished and has no decision. {@link #decide} is the only code that stores a decision. An approval
 * queues the run to go on at the earliest stage that is not finished; a rejection sets the stage back and queues it to
 * run again, or, when {@code agent.approval.max-rejections} is used up, ends the run as FAILED with the reason REJECTED.
 * No model call occurs here, and no model text, reviewer comment or approver name is ever logged.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** How many times a reviewer may reject one stage when {@code agent.approval.max-rejections} is not set. */
    static final int DEFAULT_MAX_REJECTIONS = 3;
    /** The stages a reviewer can be asked about. */
    private static final Set<String> APPROVABLE = Set.of("design", "planning");
    private static final String FINISHED = "FINISHED";

    private final RunRepository runs;
    private final ApprovalRepository decisions;
    private final ArtifactStore artifacts;
    private final RoleCatalog roles;
    private final StageProgress progress;
    /** The listed stages, in the order of the graph. */
    private final List<String> requiredFor;
    private final int maxRejections;

    /**
     * @param requiredFor    {@code agent.approval.required-for}: stage names, comma separated; empty means none
     * @param maxRejections  {@code agent.approval.max-rejections}: how many rejections of one stage end the run
     * @throws IllegalArgumentException when a name is not design or planning, or the limit is below 1
     */
    public ApprovalService(RunRepository runs, ApprovalRepository decisions, ArtifactStore artifacts, RoleCatalog roles,
                           StageProgress progress, @Value("${agent.approval.required-for:}") String requiredFor,
                           @Value("${agent.approval.max-rejections:" + DEFAULT_MAX_REJECTIONS + "}")
                           int maxRejections) {
        if (maxRejections < 1) {
            throw new IllegalArgumentException("agent.approval.max-rejections must be at least 1");
        }
        this.runs = runs;
        this.decisions = decisions;
        this.artifacts = artifacts;
        this.roles = roles;
        this.progress = progress;
        this.requiredFor = stages(requiredFor);
        this.maxRejections = maxRejections;
        log.info("event=approval_configured requiredFor={} maxRejections={}", this.requiredFor, maxRejections);
    }

    private static List<String> stages(String setting) {
        Set<String> named = new HashSet<>();
        for (String name : setting.split(",")) {
            String stage = name.strip();
            if (stage.isEmpty()) {
                continue;
            }
            if (!APPROVABLE.contains(stage)) {
                throw new IllegalArgumentException(
                        "agent.approval.required-for may list only design and planning, not " + stage);
            }
            named.add(stage);
        }
        return StageProgress.STAGES.stream().filter(named::contains).toList();
    }

    /**
     * Called by the graph when a stage has stored its artifact and is finished. When the stage is listed and no decision
     * exists yet for this version, the run starts to wait for one.
     *
     * @return true when the run now waits, so the attempt must end; false when it goes on
     */
    @Transactional
    public boolean waitForDecision(UUID runId, String stage, ArtifactRef artifact) {
        if (!requiredFor.contains(stage) || decisions.findByRunIdAndArtifactKindAndArtifactVersion(runId,
                artifact.kind(), artifact.version()).isPresent()) {
            return false;
        }
        Run run = runs.lockById(runId).orElseThrow(() ->
                new IllegalStateException("Run not found while requesting an approval"));
        run.waitForInput();
        log.info("event=approval_requested runId={} stage={} kind={} version={} fingerprint={} rejectionsLeft={}",
                runId, stage, artifact.kind(), artifact.version(), artifact.fingerprint(),
                rejectionsLeft(runId, stage));
        return true;
    }

    /** What the run waits for, or null when no listed stage has a result that waits for a decision. */
    @Transactional(readOnly = true)
    public Approval pending(UUID runId) {
        return waiting(runId).map(waiting -> {
            StoredArtifact artifact = waiting.artifact();
            return new Approval(artifact.kind(), artifact.version(), artifact.fingerprint(), artifact.summary(),
                    rejectionsLeft(runId, waiting.stage()));
        }).orElse(null);
    }

    /**
     * Stores the decision and queues, or ends, the run. Nothing is changed when it is refused.
     *
     * @throws ResponseStatusException 400 when a rejection has no comment; 404 when there is no such run; 409 when the
     *                                 run does not wait for an approval, which is also what a second decision for the
     *                                 same version meets
     */
    @Transactional
    public void decide(UUID runId, DecisionRequest request) {
        if (request.decision() == ApprovalVerdict.REJECT && (request.comment() == null || request.comment().isBlank())) {
            log.warn("event=approval_decision_refused status=400 runId={} reason=comment_required", runId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A comment is required to reject.");
        }
        Run run = runs.lockById(runId).orElseThrow(() -> {
            log.warn("event=approval_decision_refused status=404 runId={}", runId);
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found.");
        });
        Waiting waiting = run.getStatus() == RunStatus.WAITING_FOR_INPUT ? waiting(runId).orElse(null) : null;
        if (waiting == null) {
            log.warn("event=approval_decision_refused status=409 runId={} runStatus={}", runId, run.getStatus());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is not waiting for an approval.");
        }
        String stage = waiting.stage();
        StoredArtifact artifact = waiting.artifact();
        boolean rejected = request.decision() == ApprovalVerdict.REJECT;
        int rejections = rejections(runId, stage).size() + (rejected ? 1 : 0);
        decisions.save(new ApprovalDecision(runId, stage, artifact.kind(), artifact.version(), artifact.fingerprint(),
                request.decision(), request.approver(), request.comment()));
        if (rejected) {
            // The rejected result is not a finished stage: a run queued again, or retried later, starts at it.
            progress.reopen(runId, stage);
        }
        if (rejected && rejections >= maxRejections) {
            run.failWhileWaiting(RunFailureReason.REJECTED);
        } else {
            run.resumeAt(RunResume.startPoint(progress.forRun(runId), run.getKind()));
        }
        log.info("event=approval_decided runId={} stage={} kind={} version={} fingerprint={} decision={} rejections={} "
                        + "rejectionsLeft={} status={} resumedFrom={}",
                runId, stage, artifact.kind(), artifact.version(), artifact.fingerprint(), request.decision(),
                rejections, Math.max(0, maxRejections - rejections), run.getStatus(), run.getResumedFrom());
    }

    /**
     * The comments of every rejection of the stage, oldest first; empty when it was never rejected. A stage that is not
     * listed is never held for a decision, so it is not looked up.
     */
    @Transactional(readOnly = true)
    public List<String> rejectionComments(UUID runId, String stage) {
        if (!requiredFor.contains(stage)) {
            return List.of();
        }
        return rejections(runId, stage).stream().map(ApprovalDecision::getComment).toList();
    }

    /** The first listed stage, in graph order, that is finished and whose latest artifact has no decision. */
    private Optional<Waiting> waiting(UUID runId) {
        Map<String, String> states = progress.forRun(runId);
        for (String stage : requiredFor) {
            if (!FINISHED.equals(states.get(stage))) {
                continue;
            }
            Optional<StoredArtifact> latest = artifacts.latest(runId, roles.get(stage).output().kind());
            if (latest.isPresent() && decisions.findByRunIdAndArtifactKindAndArtifactVersion(runId,
                    latest.get().kind(), latest.get().version()).isEmpty()) {
                return Optional.of(new Waiting(stage, latest.get()));
            }
        }
        return Optional.empty();
    }

    private List<ApprovalDecision> rejections(UUID runId, String stage) {
        return decisions.findByRunIdAndStageOrderByIdAsc(runId, stage).stream()
                .filter(decision -> decision.getDecision() == ApprovalVerdict.REJECT).toList();
    }

    private int rejectionsLeft(UUID runId, String stage) {
        return Math.max(0, maxRejections - rejections(runId, stage).size());
    }

    private record Waiting(String stage, StoredArtifact artifact) { }

    /** The body of a decision: what the reviewer decides, who decides, and why. */
    @Schema(name = "DecisionRequest")
    public record DecisionRequest(
            @NotNull ApprovalVerdict decision,
            @NotBlank @Size(max = 100) @Schema(description = "The name of the reviewer.") String approver,
            @Size(max = 5000) @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                    description = "The reviewer's reason. Required for REJECT; it is given to the agent when the "
                            + "stage runs again.") String comment) { }

    /** What a run waits for: the result of a stage, and how many more times a reviewer may reject it. */
    @Schema(name = "Approval", description = "The result of a stage that waits for a reviewer's decision. Shown on the "
            + "run only while it waits; decide with POST /v1/runs/{id}/decisions.")
    public record Approval(
            @Schema(description = "The kind of the artifact that waits for a decision.") String kind,
            @Schema(description = "The version of that artifact. Read it with GET /v1/runs/{id}/artifacts/{kind}/{version}.")
            int version,
            @Schema(description = "The fingerprint of exactly that version.") String fingerprint,
            @Schema(description = "A counts-only summary of the artifact.") String summary,
            @Schema(description = "How many rejections of this stage are left before the run ends with the reason "
                    + "REJECTED.") int rejectionsLeft) { }
}
