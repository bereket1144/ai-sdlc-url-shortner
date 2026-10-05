package io.github.bereket1144.agentcontroller.run;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Spring Data provides the implementation for Run entities identified by UUID.
public interface RunRepository extends JpaRepository<Run, UUID> {

    // Serialize budget reservations for this run while their short transactions read and charge usage.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Run r where r.id = :id")
    Optional<Run> lockById(@Param("id") UUID id);

    // Hold this row until the claim transaction commits so another dispatcher cannot claim it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Run> findFirstByStatusOrderByCreatedAtAscIdAsc(RunStatus status);
}
