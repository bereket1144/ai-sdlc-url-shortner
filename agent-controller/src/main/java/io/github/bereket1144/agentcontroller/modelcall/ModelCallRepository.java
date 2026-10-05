package io.github.bereket1144.agentcontroller.modelcall;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/**
 * Only what the recorder needs. It extends the bare {@link Repository} marker rather than CrudRepository, so it has
 * no delete and no other way to change a stored record.
 */
interface ModelCallRepository extends Repository<ModelCallEntity, Long> {

    <S extends ModelCallEntity> S save(S entity);

    List<ModelCallEntity> findByRunIdOrderByIdAsc(UUID runId);
}
