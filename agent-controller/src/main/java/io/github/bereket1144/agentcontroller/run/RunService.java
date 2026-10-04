package io.github.bereket1144.agentcontroller.run;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunService {

    private final RunRepository runRepository;

    public RunService(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Transactional
    public Run createRun(String requirement) {
        return runRepository.save(new Run(requirement));
    }
}
