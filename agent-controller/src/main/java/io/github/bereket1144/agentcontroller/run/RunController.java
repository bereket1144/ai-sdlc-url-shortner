package io.github.bereket1144.agentcontroller.run;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RunController.RUNS_PATH)
public class RunController {

    /** The only place the path is written; the Location header is built from it too. */
    static final String RUNS_PATH = "/v1/runs";

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public ResponseEntity<RunCreatedResponse> createRun(@Valid @RequestBody RunRequest request) {
        Run run = runService.createRun(request.requirement());
        return ResponseEntity.created(URI.create(RUNS_PATH + "/" + run.getId()))
                .body(new RunCreatedResponse(run.getId()));
    }

    @GetMapping("/{id}")
    public RunResponse getRun(@PathVariable UUID id) {
        return RunResponse.from(runService.getRun(id));
    }
}
