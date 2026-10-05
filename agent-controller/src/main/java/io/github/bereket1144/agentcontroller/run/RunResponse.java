package io.github.bereket1144.agentcontroller.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.swagger.v3.oas.annotations.media.Schema;

/** What a client sees when it reads a run. A separate record so that the entity is never serialised. */
public record RunResponse(UUID id, String requirement,
        @Schema(description = "GREENFIELD builds from nothing; BROWNFIELD changes the result of the run in baseRunId.")
        RunKind kind,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "The earlier run that this run changes. Left out for a GREENFIELD run.")
        UUID baseRunId,
        RunStatus status, Instant createdAt, Instant updatedAt,
        Long version,
        @Schema(description = "How many times the run has been queued: 1 until it is retried.")
        int attempt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "Where the latest attempt started: a stage name, or START for the beginning. Left out "
                + "until the run is retried or is queued again after a reviewer's decision.")
        String resumedFrom,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Failure failureReason,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ClarificationService.Awaiting awaiting,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ApprovalService.Approval approval,
        List<StageState> stages,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ArtifactSummary> artifacts,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String commit,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Verification verification) {

    static RunResponse from(Run run, List<StageState> stages, List<ArtifactSummary> artifacts,
                            Optional<VerificationResult> verification, ClarificationService.Awaiting awaiting,
                            ApprovalService.Approval approval) {
        return new RunResponse(run.getId(), run.getRequirement(), run.getKind(), run.getBaseRunId(), run.getStatus(),
                run.getCreatedAt(), run.getUpdatedAt(), run.getVersion(), run.getAttempt(), run.getResumedFrom(),
                run.getStatus() == RunStatus.FAILED
                        ? new Failure(run.getFailureReason(), run.getFailureReason().message()) : null,
                awaiting, approval, stages, artifacts, verification.map(VerificationResult::commitId).orElse(null),
                verification.map(result -> new Verification(result.outcome().name(), result.testsRun(),
                        result.testsPassed(), result.testsFailed())).orElse(null));
    }

    @Schema(name = "StageState", description = "One SDLC stage and where it is: NOT_STARTED, RUNNING, FINISHED or FAILED.")
    public record StageState(String stage, String state) { }

    @Schema(name = "ArtifactSummary", description = "One stored artifact: its kind, version and a counts-only summary.")
    public record ArtifactSummary(String kind, int version, String summary) { }

    @Schema(name = "Verification", description = "The latest build and test of the run's code: PASSED, FAILED or "
            + "NO_RESULT, with the test counts.")
    public record Verification(String outcome, int testsRun, int testsPassed, int testsFailed) { }

    @Schema(name = "RunFailure", description = "A fixed failure code and its fixed reviewer-facing message.")
    public record Failure(RunFailureReason code, String message) { }
}
