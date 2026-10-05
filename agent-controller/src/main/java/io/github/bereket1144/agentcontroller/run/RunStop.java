package io.github.bereket1144.agentcontroller.run;

import java.util.UUID;

import io.github.bereket1144.agentcontroller.workflow.RunResume;
import io.github.bereket1144.agentcontroller.workflow.StageProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stops a run at a reviewer's request and queues a stopped run again. A run that is not running stops at once; a RUNNING
 * run is only asked to stop, and the thread that runs it ends the attempt at its next safe point (see
 * {@link StopCheck}), which is a cooperative stop. A stopped run goes back to where it was: the earliest stage that is
 * not finished, or, when it was waiting for answers or for a decision, the same wait, which is read from stored data.
 * This is its own class, as {@link RunRetry} is, because RunService holds only the run's own state changes.
 */
@Service
public class RunStop {

    private static final Logger log = LoggerFactory.getLogger(RunStop.class);

    private final RunRepository runs;
    private final StageProgress progress;
    private final ClarificationService clarification;
    private final ApprovalService approvals;

    public RunStop(RunRepository runs, StageProgress progress, ClarificationService clarification,
                   ApprovalService approvals) {
        this.runs = runs;
        this.progress = progress;
        this.clarification = clarification;
        this.approvals = approvals;
    }

    /**
     * Stops the run at once, or asks it to stop, or refuses and changes nothing.
     *
     * @return the run: STOPPED when it was stopped at once, still RUNNING when it was asked to stop
     * @throws ResponseStatusException 404 when there is no such run; 409 when it has SUCCEEDED, FAILED or is STOPPED
     */
    @Transactional
    public Run stop(UUID runId) {
        Run run = lock(runId, "run_stop_not_found");
        RunStatus before = run.getStatus();
        if (before == RunStatus.RUNNING) {
            runs.requestStop(runId);
            log.info("event=run_stop_requested runId={}", runId);
            return run;
        }
        try {
            run.stopAtOnce();
        } catch (IllegalStateException refusal) {
            log.warn("event=run_stop_refused status=409 runId={} runStatus={}", runId, before);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A finished or stopped run cannot be stopped.");
        }
        progress.reopenRunning(runId);
        log.info("event=run_stopped runId={} stoppedFrom={}", runId, before);
        return run;
    }

    /**
     * Queues a stopped run again, or puts it back into the wait it was in, or refuses and changes nothing. A resume does
     * not use up an attempt. A run that was waiting is not queued: its question or approval request is stored, so it
     * waits for it again, and no model is called.
     *
     * @return the run: CREATED when it was queued, WAITING_FOR_INPUT when it went back to waiting
     * @throws ResponseStatusException 404 when there is no such run; 409 when it is not STOPPED
     */
    @Transactional
    public Run resume(UUID runId) {
        Run run = lock(runId, "run_resume_not_found");
        RunStatus before = run.getStatus();
        boolean waiting = clarification.awaiting(runId) != null || approvals.pending(runId) != null;
        String startsAt = RunResume.startPoint(progress.forRun(runId), run.getKind());
        try {
            if (waiting) {
                run.waitAgain();
            } else {
                run.resumeStopped(startsAt);
            }
        } catch (IllegalStateException refusal) {
            log.warn("event=run_resume_refused status=409 runId={} runStatus={}", runId, before);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a stopped run can be resumed.");
        }
        log.info("event=run_resumed runId={} attempt={} status={} startsAt={}", runId, run.getAttempt(),
                run.getStatus(), waiting ? "none" : startsAt);
        return run;
    }

    private Run lock(UUID runId, String notFoundEvent) {
        return runs.lockById(runId).orElseThrow(() -> {
            log.warn("event={} status=404 runId={}", notFoundEvent, runId);
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found.");
        });
    }
}
