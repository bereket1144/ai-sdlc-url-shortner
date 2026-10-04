package io.github.bereket1144.agentcontroller.run;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/runs")
public class RunController {
    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public void createRun(@Valid @RequestBody RunRequest request) {
        // Do not report success until the service actually creates a run.
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Run creation is not implemented yet");
    }
}
