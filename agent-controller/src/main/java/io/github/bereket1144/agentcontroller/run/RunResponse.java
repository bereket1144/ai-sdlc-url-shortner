package io.github.bereket1144.agentcontroller.run;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.swagger.v3.oas.annotations.media.Schema;

/** What a client sees when it reads a run. A separate record so that the entity is never serialised. */
public record RunResponse(UUID id, String requirement, RunStatus status, Instant createdAt, Instant updatedAt,
        Long version,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Failure failureReason,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        ClarificationService.Awaiting awaiting,
        List<StageState> stages,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ArtifactSummary> artifacts,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String commit,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Verification verification) {

    static RunResponse from(Run run, List<StageState> stages, List<ArtifactSummary> artifacts,
                            Optional<VerificationResult> verification, ClarificationService.Awaiting awaiting) {
        return new RunResponse(run.getId(), run.getRequirement(), run.getStatus(), run.getCreatedAt(),
                run.getUpdatedAt(), run.getVersion(), run.getStatus() == RunStatus.FAILED
                        ? new Failure(run.getFailureReason(), run.getFailureReason().message()) : null,
                awaiting, stages, artifacts, verification.map(VerificationResult::commitId).orElse(null),
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
