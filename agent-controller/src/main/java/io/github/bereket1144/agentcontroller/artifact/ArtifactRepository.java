package io.github.bereket1144.agentcontroller.artifact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/**
 * Only what the store needs. It extends the bare {@link Repository} marker rather than CrudRepository, so it has no
 * delete and no other way to change a stored artifact.
 */
interface ArtifactRepository extends Repository<ArtifactEntity, Long> {

    <S extends ArtifactEntity> S save(S entity);

    Optional<ArtifactEntity> findFirstByRunIdAndKindOrderByVersionDesc(UUID runId, String kind);

    Optional<ArtifactEntity> findByRunIdAndKindAndVersion(UUID runId, String kind, int version);

    List<ArtifactEntity> findByRunIdOrderByIdAsc(UUID runId);
}
