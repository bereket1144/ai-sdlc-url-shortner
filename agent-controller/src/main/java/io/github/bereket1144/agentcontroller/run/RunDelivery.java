package io.github.bereket1144.agentcontroller.run;

import java.util.UUID;

import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.workspace.DeliveryBlockedException;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceMerge;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Completes a verified run with automatic delivery, serializing this final action with Stop. */
@Service
@ConditionalOnProperty(name = "workspace.auto-merge", havingValue = "true")
public class RunDelivery {

    private final RunRepository runs;
    private final RunService service;
    private final VerificationRecords verifications;
    private final WorkspaceMerge merge;

    public RunDelivery(RunRepository runs, RunService service, VerificationRecords verifications,
                       WorkspaceMerge merge) {
        this.runs = runs;
        this.service = service;
        this.verifications = verifications;
        this.merge = merge;
    }

    @Transactional
    public void complete(UUID runId) {
        Run run = runs.lockById(runId).orElseThrow(() -> new IllegalStateException("Run not found during delivery"));
        if (run.isStopRequested()) {
            throw new RunStoppedException();
        }
        if (run.getStatus() != RunStatus.RUNNING) {
            throw new DeliveryBlockedException("RUN_NOT_RUNNING");
        }
        verifications.latest(runId).filter(VerificationResult::passed)
                .orElseThrow(() -> new DeliveryBlockedException("VERIFICATION_NOT_PASSED"));
        // Hold the row lock through Git and success. Stop either wins before delivery, or observes a finished run.
        merge.merge(runId);
        service.succeedRun(runId);
    }
}
