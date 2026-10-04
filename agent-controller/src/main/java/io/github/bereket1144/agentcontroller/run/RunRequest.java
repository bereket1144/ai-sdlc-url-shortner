package io.github.bereket1144.agentcontroller.run;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

/**
 * The create-run request.
 * <p>
 * "Characters" means Unicode code points, not Java's UTF-16 units: an emoji is one character to the person who
 * typed it, and counting it as two would refuse a requirement that is exactly at the limit.
 */
public record RunRequest(
        @Schema(maxLength = RunRequest.MAX_REQUIREMENT_CHARACTERS) // the library does not read @CodePointLength
        @NotBlank @CodePointLength(max = RunRequest.MAX_REQUIREMENT_CHARACTERS) String requirement) {

    /** The longest requirement accepted, in characters. The only place this number is written. */
    public static final int MAX_REQUIREMENT_CHARACTERS = 20_000;
}
