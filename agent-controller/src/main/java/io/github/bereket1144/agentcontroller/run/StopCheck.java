package io.github.bereket1144.agentcontroller.run;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells the thread that runs a run whether a reviewer asked it to stop. The thread asks at its safe points: before a
 * stage attempt, before a model call attempt, before a build and, while a build runs, now and then. The answer comes
 * from the database, so a request made on another thread, or another instance, is seen at the next safe point.
 */
@Component
public class StopCheck {

    private static final Logger log = LoggerFactory.getLogger(StopCheck.class);

    private final RunRepository runs;

    public StopCheck(RunRepository runs) {
        this.runs = runs;
    }

    public boolean requested(UUID runId) {
        return runs.findStopRequested(runId).orElse(false);
    }

    /** @throws RunStoppedException when a stop was requested for the run */
    public void check(UUID runId) {
        if (requested(runId)) {
            log.info("event=run_stop_seen runId={}", runId);
            throw new RunStoppedException();
        }
    }
}
