package io.github.bereket1144.agentcontroller.run;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Only describes, for the API document, the flat problem JSON that {@link ApiExceptionHandler} sends. The
 * framework's own ProblemDetail class cannot be used: its extension map would be listed as a nested "properties"
 * field, while the real body puts extensions such as "errors" at the top level.
 */
@Schema(name = "Problem", description = "An error, as RFC 9457 problem details.")
public record ProblemBody(
        @Schema(description = "A stable identifier of the kind of problem.") String type,
        @Schema(description = "A short title of the problem.") String title,
        @Schema(description = "The HTTP status code.") int status,
        @Schema(description = "What went wrong, in words.") String detail,
        @Schema(description = "Only on a validation failure: the fields that were refused.")
        List<ApiExceptionHandler.FieldProblem> errors) {
}
