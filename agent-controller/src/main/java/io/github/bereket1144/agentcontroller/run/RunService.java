package io.github.bereket1144.agentcontroller.run;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
}
