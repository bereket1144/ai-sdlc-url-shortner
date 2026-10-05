package io.github.bereket1144.agentcontroller.artifact;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.RoleDefinition;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.modelcall.InputFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The versioned store of what each stage produced. Storing a kind again adds the next version; a stored version is
 * never changed, so {@link #store}, {@link #find} and {@link #forRun} are the only operations and there is no update or
 * delete. The kind comes from the producing role's declared output, so a role cannot store a kind it does not own.
 */
@Service
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final ArtifactRepository repository;
    private final RoleCatalog roles;
    private final JsonMapper mapper = JsonMapper.shared();

    ArtifactStore(ArtifactRepository repository, RoleCatalog roles) {
        this.repository = repository;
        this.roles = roles;
    }

    /**
     * Stores the artifact as the next version of its kind for the run and returns its reference. It fails, and stores
     * nothing, when the role is unknown or the run does not exist. Two writers storing the same kind for the same run
     * at once cannot both get the same version: the database refuses the second, and that failure is passed on.
     */
    public ArtifactRef store(NewArtifact artifact) {
        RoleDefinition role = roles.get(artifact.role());
        String kind = role.output().kind();
        int version = repository.findFirstByRunIdAndKindOrderByVersionDesc(artifact.runId(), kind)
                .map(latest -> latest.version() + 1)
                .orElse(1);
        // The fingerprint is of exactly the text that is stored, so it can be checked against what is read back.
        String content = mapper.writeValueAsString(artifact.content());
        String fingerprint = InputFingerprint.sha256(content);
        ModelProfile profile = artifact.profile();
        ArtifactEntity stored = repository.save(new ArtifactEntity(artifact.runId(), kind, version, content,
                artifact.summary(), fingerprint, artifact.role(), role.version(), profile.name(), profile.model(),
                artifact.consumed()));
        // Ids, kind, version and fingerprint only: the content and the summary are stored, and never logged.
        log.info("event=artifact_stored runId={} role={} kind={} version={} fingerprint={} consumedCount={}",
                artifact.runId(), artifact.role(), kind, version, fingerprint, artifact.consumed().size());
        return stored.toRef();
    }

    /** One version of a kind for a run; empty when the run, the kind or the version is unknown. */
    public Optional<StoredArtifact> find(UUID runId, String kind, int version) {
        return repository.findByRunIdAndKindAndVersion(runId, kind, version)
                .map(entity -> entity.toStoredArtifact(mapper));
    }

    /** Every artifact of the run, with the versions each one consumed, in the order stored; empty when none. */
    public List<StoredArtifact> forRun(UUID runId) {
        return repository.findByRunIdOrderByIdAsc(runId).stream()
                .map(entity -> entity.toStoredArtifact(mapper))
                .toList();
    }

    /**
     * Everything the caller knows about one stage output.
     *
     * @param runId    the run the artifact belongs to
     * @param role     the producing role id; its output kind and version are looked up and stored with the artifact
     * @param content  the validated content
     * @param summary  a short summary of the content
     * @param profile  the profile of the call that produced it; its name and model identifier are stored
     * @param consumed the artifact versions that went into the prompt; empty when none
     */
    public record NewArtifact(UUID runId, String role, JsonNode content, String summary, ModelProfile profile,
                              List<ArtifactRef> consumed) {
        public NewArtifact {
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(profile, "profile must not be null");
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("summary must not be blank");
            }
            consumed = List.copyOf(consumed);
        }
    }

    /** One stored artifact, as read back. Every field is what was stored. */
    public record StoredArtifact(UUID runId, String kind, int version, JsonNode content, String summary,
                                 String fingerprint, String role, String roleVersion, String profile, String model,
                                 List<ArtifactRef> consumed) { }
}
