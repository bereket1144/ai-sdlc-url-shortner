package io.github.bereket1144.agentcontroller.run;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

    // The value in the database now, read without loading the run, so that the thread that runs the graph sees a
    // request that was committed after it loaded the run.
    @Query("select r.stopRequested from Run r where r.id = :id")
    Optional<Boolean> findStopRequested(@Param("id") UUID id);

    // A bulk update on purpose: it leaves the version alone, so the thread that is running the graph cannot fail its
    // next state change with an optimistic-lock error because of a stop request.
    @Modifying
    @Query("update Run r set r.stopRequested = true where r.id = :id")
    void requestStop(@Param("id") UUID id);
}
