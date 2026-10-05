package io.github.bereket1144.agentcontroller.artifact;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.InputAccessDeniedException;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.RoleDefinition;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the artifact versions a stage is handed into the prompt inputs of one role. The caller names the exact
 * versions (for a kind that needs approval, the approved one) and this reads those and nothing else, and only kinds
 * the role's definition lists.
 */
@Service
public class StageInputs {

    private static final Logger log = LoggerFactory.getLogger(StageInputs.class);

    private final ArtifactStore store;
    private final RoleCatalog roles;
    private final JsonMapper mapper = JsonMapper.shared();

    StageInputs(ArtifactStore store, RoleCatalog roles) {
        this.store = store;
        this.roles = roles;
    }

    /**
     * Fails with {@link InputAccessDeniedException} before any artifact is read when the role does not list the kind
     * of one of the references. The result holds the prompt inputs and, in the same order, the references that went
     * into them: these are the versions to record as consumed when the answer is stored.
     */
    public Resolved resolve(UUID runId, String roleId, List<ArtifactRef> refs) {
        Objects.requireNonNull(runId, "runId must not be null");
        RoleDefinition role = roles.get(roleId);
        List<ArtifactRef> wanted = List.copyOf(refs);
        for (ArtifactRef ref : wanted) {
            InputKind kind = inputKind(ref.kind());
            if (!role.inputs().contains(kind)) {
                log.info("event=input_access_denied runId={} role={} kind={}", runId, roleId, kind);
                throw new InputAccessDeniedException(roleId, kind);
            }
        }
        List<PromptInput> inputs = wanted.stream().map(ref -> read(runId, ref)).toList();
        log.info("event=stage_inputs_resolved runId={} role={} inputCount={}", runId, roleId, inputs.size());
        return new Resolved(inputs, wanted);
    }

    private PromptInput read(UUID runId, ArtifactRef ref) {
        StoredArtifact artifact = store.find(runId, ref.kind(), ref.version()).orElseThrow(
                () -> new IllegalArgumentException("No artifact " + ref.kind() + " version " + ref.version()
                        + " for run " + runId));
        return new PromptInput(inputKind(ref.kind()), String.valueOf(ref.version()),
                mapper.writeValueAsString(artifact.content()), artifact.summary(), false);
    }

    /** The artifact kind "impactAnalysis" is the input kind IMPACT_ANALYSIS_ARTIFACT, and so on for every kind. */
    private static InputKind inputKind(String artifactKind) {
        String name = artifactKind.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase() + "_ARTIFACT";
        try {
            return InputKind.valueOf(name);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown artifact kind: " + artifactKind, exception);
        }
    }

    /**
     * @param inputs   the prompt inputs, one per reference
     * @param consumed the references the inputs were read from, in the same order
     */
    public record Resolved(List<PromptInput> inputs, List<ArtifactRef> consumed) { }
}
