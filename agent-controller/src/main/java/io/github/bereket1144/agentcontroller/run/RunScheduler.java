package io.github.bereket1144.agentcontroller.run;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "run.dispatch.polling-enabled", havingValue = "true", matchIfMissing = true)
public class RunScheduler {

    private final RunDispatcher runDispatcher;

    public RunScheduler(RunDispatcher runDispatcher) {
        this.runDispatcher = runDispatcher;
    }

    // Fixed delay waits for dispatch to finish before this instance polls again.
    @Scheduled(fixedDelayString = "${run.dispatch.poll-delay-ms:5000}")
    public void poll() {
        // Drain the backlog in one poll. A failing dispatch throws out of this loop, as before.
        while (runDispatcher.dispatchNext()) {
            // dispatchNext already logs each run it starts.
        }
    }
}
