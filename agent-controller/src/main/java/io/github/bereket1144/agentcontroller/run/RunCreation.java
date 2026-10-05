package io.github.bereket1144.agentcontroller.run;

import java.util.UUID;

import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores a run from a create request, after checking the kind and the base run together. This is its own class, as
 * {@link RunRetry} is, because RunService holds only the run's own state changes and is used with the run repository
 * alone, while this check also reads the stored verification results. A refused request stores nothing.
 */
@Service
public class RunCreation {

    private static final Logger log = LoggerFactory.getLogger(RunCreation.class);

    private final RunRepository runs;
    private final RunService runService;
    private final VerificationRecords verifications;

    public RunCreation(RunRepository runs, RunService runService, VerificationRecords verifications) {
        this.runs = runs;
        this.runService = runService;
        this.verifications = verifications;
    }

    /**
     * A request without a kind is a GREENFIELD run. A BROWNFIELD run needs a base run that exists, has SUCCEEDED and has
     * a stored commit; a GREENFIELD run must not name one.
     *
     * @return the stored run
     * @throws ResponseStatusException 400, with fixed text that never repeats what the caller sent
     */
    public Run create(RunRequest request) {
        RunKind kind = request.kind() == null ? RunKind.GREENFIELD : request.kind();
        UUID baseRunId = request.baseRunId();
        if (kind == RunKind.GREENFIELD && baseRunId != null) {
            throw refused("base_on_greenfield", "A GREENFIELD run must not have a baseRunId.");
        }
        if (kind == RunKind.BROWNFIELD) {
            if (baseRunId == null) {
                throw refused("base_missing", "A BROWNFIELD run needs a baseRunId.");
            }
            requireUsableBase(baseRunId);
        }
        Run run = runService.createRun(request.requirement(), kind, baseRunId);
        log.info("event=run_created runId={} kind={} baseRunId={}", run.getId(), kind, baseRunId);
        return run;
    }

    private void requireUsableBase(UUID baseRunId) {
        Run base = runs.findById(baseRunId)
                .orElseThrow(() -> refused("base_not_found", "The base run was not found."));
        if (base.getStatus() != RunStatus.SUCCEEDED) {
            throw refused("base_not_succeeded", "The base run has not SUCCEEDED.");
        }
        if (verifications.latest(baseRunId).isEmpty()) {
            throw refused("base_without_commit", "The base run has no stored commit.");
        }
    }

    private static ResponseStatusException refused(String reason, String detail) {
        log.warn("event=run_request_refused status=400 reason={}", reason);
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
