package io.github.bereket1144.agentcontroller.run;

import java.time.Instant;
import java.util.UUID;

/** What a client sees when it reads a run. A separate record so that the entity is never serialised. */
public record RunResponse(UUID id, String requirement, RunStatus status, Instant createdAt, Instant updatedAt,
        Long version) {

    static RunResponse from(Run run) {
        return new RunResponse(run.getId(), run.getRequirement(), run.getStatus(), run.getCreatedAt(),
                run.getUpdatedAt(), run.getVersion());
    }
}
