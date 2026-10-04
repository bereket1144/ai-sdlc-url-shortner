package io.github.bereket1144.agentcontroller.run;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/runs")
public class RunController {
    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public ResponseEntity<RunCreatedResponse> createRun(@Valid @RequestBody RunRequest request) {
        Run run = runService.createRun(request.requirement());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RunCreatedResponse(run.getId()));
    }
}
