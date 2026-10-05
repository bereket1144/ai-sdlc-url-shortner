package io.github.bereket1144.agentcontroller.run;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClarificationRepository extends JpaRepository<ClarificationBatch, Long> {
    List<ClarificationBatch> findByRunIdOrderByArtifactVersionAsc(UUID runId);
}
