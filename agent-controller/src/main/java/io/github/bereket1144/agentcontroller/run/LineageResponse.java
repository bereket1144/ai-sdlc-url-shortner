package io.github.bereket1144.agentcontroller.run;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Why a run's output exists, built only from its stored artifacts and the references each one consumed. The lists
 * are empty before the run has stored anything; the commit and the outcome are left out until a build has run.
 */
public record LineageResponse(
        List<LineageArtifact> artifacts,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String commit,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String verificationOutcome,
        List<LineageFile> files) {

    @Schema(name = "LineageArtifact", description = "One stored artifact and the artifact versions it was built "
            + "from (kind, version and fingerprint, as stored when it was made).")
    public record LineageArtifact(String kind, int version, String fingerprint, String summary,
                                  List<ArtifactRef> builtFrom) { }

    @Schema(name = "LineageFile", description = "One file of the latest development artifact and the criterion ids "
            + "that artifact says it serves (its affectedCriteria).")
    public record LineageFile(String path, List<String> criteria) { }
}
