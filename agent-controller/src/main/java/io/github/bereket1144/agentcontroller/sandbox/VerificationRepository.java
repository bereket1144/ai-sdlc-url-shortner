package io.github.bereket1144.agentcontroller.sandbox;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/** Only what the records need: it extends the bare {@link Repository} marker, so it has no delete or update. */
interface VerificationRepository extends Repository<VerificationEntity, Long> {

    <S extends VerificationEntity> S save(S entity);

    Optional<VerificationEntity> findFirstByRunIdOrderByIdDesc(UUID runId);
}
