package io.github.bereket1144.agentcontroller.run;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final RunRepository runRepository;

    public RunService(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Transactional
    public Run createRun(String requirement) {
        return runRepository.save(new Run(requirement));
    }

    /**
     * Reads a run, or fails with a 404 that ApiExceptionHandler renders as problem+json.
     * The detail is fixed text: it does not repeat the id or say anything about storage.
     */
    @Transactional(readOnly = true)
    public Run getRun(UUID id) {
        return runRepository.findById(id).orElseThrow(() -> {
            log.warn("event=run_not_found status=404 runId={}", id);
            return new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found.");
        });
    }

    /** Returns the loaded run so dispatch can use its inputs after this transaction commits. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Run> claimNextRun() {
        Optional<Run> run = runRepository.findFirstByStatusOrderByCreatedAtAscIdAsc(RunStatus.CREATED);
        if (run.isEmpty()) {
            return Optional.empty();
        }

        Run claimed = run.get();
        claimed.start();
        log.info("event=run_claim_selected runId={}", claimed.getId());
        return Optional.of(claimed);
    }

    /** The claimed Run is detached after its transaction, so load it again for this state change. */
    @Transactional
    public void succeedRun(UUID runId) {
        Run run = runRepository.findById(runId).orElseThrow(() -> {
            log.warn("event=run_success_target_missing runId={}", runId);
            return new IllegalStateException("Run not found while recording success");
        });
        run.succeed();
        log.info("event=run_success_transition_requested runId={}", runId);
    }

    /** Commit FAILED independently so a caller can rethrow the workflow error without rolling this back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRun(UUID runId) {
        Run run = runRepository.findById(runId).orElseThrow(() -> {
            log.warn("event=run_failure_target_missing runId={}", runId);
            return new IllegalStateException("Run not found while recording failure");
        });
        run.fail();
        log.info("event=run_failure_transition_requested runId={}", runId);
    }
}
