package io.github.bereket1144.agentcontroller.run;

import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import io.github.bereket1144.agentcontroller.workspace.DeliveryBlockedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);
    private static final int MAX_CAUSE_DEPTH = 20; // stops a loop in a cause chain

    private final RunService runService;
    private final WorkflowRunner workflowRunner;
    private final Optional<RunDelivery> delivery;

    public RunDispatcher(RunService runService, WorkflowRunner workflowRunner) {
        this(runService, workflowRunner, Optional.empty());
    }

    @Autowired
    public RunDispatcher(RunService runService, WorkflowRunner workflowRunner, Optional<RunDelivery> delivery) {
        this.runService = runService;
        this.workflowRunner = workflowRunner;
        this.delivery = delivery;
    }

    public boolean dispatchNext() {
        Optional<Run> claimedRun = runService.claimNextRun();
        if (claimedRun.isEmpty()) {
            log.debug("event=run_dispatch_idle");
            return false;
        }

        Run run = claimedRun.get();
        UUID runId = run.getId();
        // The claim has its own transaction, so its RUNNING state is committed before the graph starts.
        log.info("event=run_dispatch_started runId={} attempt={}", runId, run.getAttempt());
        try {
            // A run that was retried says where its attempt starts; a first attempt starts at the beginning.
            var result = run.getResumedFrom() == null
                    ? workflowRunner.run(runId, run.getRequirement(), run.getKind(), run.getBaseRunId())
                    : workflowRunner.retry(runId, run.getRequirement(), run.getKind(), run.getBaseRunId(),
                            run.getResumedFrom());
            if (result.<Boolean>value("waitingForInput").orElse(false)) {
                // The graph stopped to wait for answers or for a reviewer's decision: neither a success nor a failure.
                log.info("event=run_dispatch_waiting runId={}", runId);
                return true;
            }
        } catch (RuntimeException | Error workflowFailure) {
            if (RunStoppedException.in(workflowFailure)) {
                // A reviewer asked the run to stop and the graph reached a safe point: neither a failure nor a success.
                return recordStop(runId);
            }
            try {
                runService.failRun(runId, workflowFailure);
            } catch (RunStoppedException stopped) {
                return recordStop(runId);
            } catch (RuntimeException recordingFailure) {
                workflowFailure.addSuppressed(recordingFailure);
                log.error("event=run_failure_recording_failed runId={} errorType={}",
                        runId, recordingFailure.getClass().getName());
            }
            log.error("event=run_dispatch_failed runId={} errorType={}",
                    runId, rootCause(workflowFailure).getClass().getName());
            throw workflowFailure;
        }
        try {
            if (delivery.isPresent()) {
                delivery.get().complete(runId);
            } else {
                runService.succeedRun(runId);
            }
        } catch (RunStoppedException stopped) {
            return recordStop(runId);
        } catch (DeliveryBlockedException blocked) {
            try {
                runService.failRun(runId, blocked);
            } catch (RunStoppedException stopped) {
                return recordStop(runId);
            } catch (RuntimeException recordingFailure) {
                blocked.addSuppressed(recordingFailure);
                log.error("event=run_failure_recording_failed runId={} errorType={}",
                        runId, recordingFailure.getClass().getName());
            }
            log.warn("event=run_dispatch_delivery_blocked runId={}", runId);
            throw blocked;
        } catch (RuntimeException recordingFailure) {
            // The workflow finished, so the run is not marked FAILED; this line says which run is stuck RUNNING.
            log.error("event=run_success_recording_failed runId={} errorType={}",
                    runId, recordingFailure.getClass().getName());
            throw recordingFailure;
        }
        log.info("event=run_dispatch_succeeded runId={}", runId);
        return true;
    }

    private boolean recordStop(UUID runId) {
        try {
            runService.stopRun(runId);
        } catch (RuntimeException recordingFailure) {
            // The graph stopped, so the run is not marked FAILED; this line says which run is stuck RUNNING.
            log.error("event=run_stop_recording_failed runId={} errorType={}",
                    runId, recordingFailure.getClass().getName());
            throw recordingFailure;
        }
        log.info("event=run_dispatch_stopped runId={}", runId);
        return true;
    }

    /** The graph library wraps a node failure, so the useful class name is at the end of the cause chain. */
    private static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        for (int depth = 0; root.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
            root = root.getCause();
        }
        return root;
    }
}
