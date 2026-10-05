package io.github.bereket1144.agentcontroller.run;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * The create-run request.
 * <p>
 * "Characters" means Unicode code points, not Java's UTF-16 units: an emoji is one character to the person who
 * typed it, and counting it as two would refuse a requirement that is exactly at the limit.
 * <p>
 * {@code kind} and {@code baseRunId} are checked together, against the stored runs, by {@link RunCreation}. A base is
 * only ever a run id: a path or an address cannot be written in a UUID.
 */
public record RunRequest(
        @Schema(maxLength = RunRequest.MAX_REQUIREMENT_CHARACTERS) // the library does not read @CodePointLength
        @NotBlank @CodePointLength(max = RunRequest.MAX_REQUIREMENT_CHARACTERS) String requirement,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, defaultValue = "GREENFIELD",
                description = "GREENFIELD (the default) builds from nothing. BROWNFIELD changes the result of the run "
                        + "named by baseRunId.")
        RunKind kind,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                description = "The run that a BROWNFIELD run changes. Required for BROWNFIELD and refused for "
                        + "GREENFIELD. That run must have SUCCEEDED and have a stored commit.")
        UUID baseRunId) {

    /** The longest requirement accepted, in characters. The only place this number is written. */
    public static final int MAX_REQUIREMENT_CHARACTERS = 20_000;
}
