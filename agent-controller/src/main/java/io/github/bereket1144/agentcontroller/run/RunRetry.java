package io.github.bereket1144.agentcontroller.run;

import java.util.UUID;

import io.github.bereket1144.agentcontroller.workflow.RunResume;
import io.github.bereket1144.agentcontroller.workflow.StageProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Queues a failed run again. The first retry resumes at the earliest stage that is not finished; a later one starts from
 * the beginning. The run goes back to CREATED, so the scheduler claims it like any other. This is its own class because
 * RunService holds only the run's own state changes, and choosing a start point needs the stored stage states.
 */
@Service
public class RunRetry {

    private static final Logger log = LoggerFactory.getLogger(RunRetry.class);

    /** How many attempts a run may have in all when {@code agent.run.max-attempts} is not set. */
    static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final RunRepository runs;
    private final StageProgress progress;
    private final int maxAttempts;

    /** @param maxAttempts {@code agent.run.max-attempts}: how many attempts a run may have in all, the first included */
    public RunRetry(RunRepository runs, StageProgress progress,
                    @Value("${agent.run.max-attempts:" + DEFAULT_MAX_ATTEMPTS + "}") int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("agent.run.max-attempts must be at least 1");
        }
        this.runs = runs;
        this.progress = progress;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Queues the run again, or refuses and changes nothing.
     *
     * @return the run, now CREATED with its next attempt number
     * @throws ResponseStatusException 404 when there is no such run; 409 when it is not FAILED or has no attempts left
     */
    @Transactional
    public Run retry(UUID runId) {
        Run run = runs.lockById(runId).orElseThrow(() -> {
            log.warn("event=run_retry_not_found status=404 runId={}", runId);
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found.");
        });
        // The first retry resumes where the run stopped; after that the whole graph runs again.
        String startsAt = run.getAttempt() == 1
                ? RunResume.startPoint(progress.forRun(runId), run.getKind()) : RunResume.START;
        RunStatus before = run.getStatus();
        try {
            run.retry(startsAt, maxAttempts);
        } catch (IllegalStateException refusal) {
            log.warn("event=run_retry_refused status=409 runId={} runStatus={} attempt={} maxAttempts={}",
                    runId, before, run.getAttempt(), maxAttempts);
            throw new ResponseStatusException(HttpStatus.CONFLICT, refusal.getMessage());
        }
        log.info("event=run_retry_queued runId={} attempt={} startsAt={}", runId, run.getAttempt(), startsAt);
        return run;
    }
}
