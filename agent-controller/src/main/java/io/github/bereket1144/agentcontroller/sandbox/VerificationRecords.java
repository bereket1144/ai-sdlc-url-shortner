package io.github.bereket1144.agentcontroller.sandbox;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Keeps the result of every build and test of a run, so it can be read after the run has gone on or ended. */
@Service
public class VerificationRecords {

    private static final Logger log = LoggerFactory.getLogger(VerificationRecords.class);

    private final VerificationRepository repository;

    VerificationRecords(VerificationRepository repository) {
        this.repository = repository;
    }

    public void record(UUID runId, VerificationResult result) {
        repository.save(new VerificationEntity(runId, result));
        // Ids, commit and counts only; test names and the report excerpt are stored, never logged.
        log.info("event=verification_recorded runId={} commit={} outcome={} testsRun={} testsFailed={}",
                runId, result.commitId(), result.outcome(), result.testsRun(), result.testsFailed());
    }

    /** The most recent result of the run, or empty when none was recorded. */
    public Optional<VerificationResult> latest(UUID runId) {
        return repository.findFirstByRunIdOrderByIdDesc(runId).map(VerificationEntity::toResult);
    }
}
