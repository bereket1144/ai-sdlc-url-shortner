package io.github.bereket1144.agentcontroller.run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/**
 * Only what the approvals need. It extends the bare {@link Repository} marker, so it has no delete and no way to change a
 * stored decision, and it is package-private, so only {@link ApprovalService} can use it.
 */
interface ApprovalRepository extends Repository<ApprovalDecision, Long> {

    <S extends ApprovalDecision> S save(S decision);

    Optional<ApprovalDecision> findByRunIdAndArtifactKindAndArtifactVersion(UUID runId, String artifactKind,
                                                                            int artifactVersion);

    List<ApprovalDecision> findByRunIdAndStageOrderByIdAsc(UUID runId, String stage);
}
