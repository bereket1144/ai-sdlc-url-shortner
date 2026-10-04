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
            workflowRunner.run(runId, run.getRequirement());
        } catch (RuntimeException exception) {
            log.error("event=run_dispatch_failed runId={} errorType={}", runId, exception.getClass().getName());
            throw exception;
        }
        log.info("event=run_dispatch_returned runId={}", runId);
        return true;
    }
}
