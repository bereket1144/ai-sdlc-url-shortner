package io.github.bereket1144.agentcontroller.run;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
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

    private static final String PROBLEM_JSON = "application/problem+json";

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    // The method returns a ResponseEntity, so the library cannot see the 201 status or the body type on its own.
    @Operation(summary = "Create a run", description = "Stores a new run for the given requirement text.")
    @ApiResponse(responseCode = "201", description = "The run was created; its address is in the Location header.",
            headers = @Header(name = "Location", description = "Address of the new run, /v1/runs/{id}.",
                    schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RunCreatedResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is missing, is not valid JSON, or fails validation (the problem lists the "
                    + "offending fields in 'errors').",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "415", description = "The request content type is not application/json.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping
    public ResponseEntity<RunCreatedResponse> createRun(@Valid @RequestBody RunRequest request) {
        Run run = runService.createRun(request.requirement());
        return ResponseEntity.created(URI.create(RUNS_PATH + "/" + run.getId()))
                .body(new RunCreatedResponse(run.getId()));
    }

    @Operation(summary = "Read a run", description = "Returns the stored run with the given id.")
    @ApiResponse(responseCode = "200", description = "The run.")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No run has this id.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{id}")
    public RunResponse getRun(@PathVariable UUID id) {
        return RunResponse.from(runService.getRun(id));
    }
}
