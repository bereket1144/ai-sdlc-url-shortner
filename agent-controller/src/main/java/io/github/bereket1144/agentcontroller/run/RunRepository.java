package io.github.bereket1144.agentcontroller.run;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

// Spring Data provides the implementation for Run entities identified by UUID.
public interface RunRepository extends JpaRepository<Run, UUID> {

    // Hold this row until the claim transaction commits so another dispatcher cannot claim it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Run> findFirstByStatusOrderByCreatedAtAscIdAsc(RunStatus status);
}
