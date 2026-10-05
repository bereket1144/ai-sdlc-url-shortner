package io.github.bereket1144.agentcontroller.run;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** What a client sees when it reads a run. A separate record so that the entity is never serialised. */
public record RunResponse(UUID id, String requirement, RunStatus status, Instant createdAt, Instant updatedAt,
        Long version,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Failure failureReason) {

    static RunResponse from(Run run) {
        return new RunResponse(run.getId(), run.getRequirement(), run.getStatus(), run.getCreatedAt(),
                run.getUpdatedAt(), run.getVersion(), run.getStatus() == RunStatus.FAILED
                        ? new Failure(run.getFailureReason(), run.getFailureReason().message()) : null);
    }

    @Schema(name = "RunFailure", description = "A fixed failure code and its fixed reviewer-facing message.")
    public record Failure(RunFailureReason code, String message) { }
}
