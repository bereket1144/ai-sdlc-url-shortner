package io.github.bereket1144.agentcontroller.run;

import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);
    private static final int MAX_CAUSE_DEPTH = 20; // stops a loop in a cause chain

    private final RunService runService;
    private final WorkflowRunner workflowRunner;

    public RunDispatcher(RunService runService, WorkflowRunner workflowRunner) {
        this.runService = runService;
        this.workflowRunner = workflowRunner;
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
        log.info("event=run_dispatch_started runId={}", runId);
        try {
            workflowRunner.run(runId, run.getRequirement(), run.getKind());
        } catch (RuntimeException | Error workflowFailure) {
            try {
                runService.failRun(runId);
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
            runService.succeedRun(runId);
        } catch (RuntimeException recordingFailure) {
            // The workflow finished, so the run is not marked FAILED; this line says which run is stuck RUNNING.
            log.error("event=run_success_recording_failed runId={} errorType={}",
                    runId, recordingFailure.getClass().getName());
            throw recordingFailure;
        }
        log.info("event=run_dispatch_succeeded runId={}", runId);
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
