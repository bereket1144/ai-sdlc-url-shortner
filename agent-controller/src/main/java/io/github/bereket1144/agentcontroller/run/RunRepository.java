package io.github.bereket1144.agentcontroller.run;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

// Spring Data provides the implementation for Run entities identified by UUID.
public interface RunRepository extends JpaRepository<Run, UUID> {
}
