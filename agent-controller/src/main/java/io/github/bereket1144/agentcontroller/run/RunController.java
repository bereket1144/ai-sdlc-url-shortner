package io.github.bereket1144.agentcontroller.run;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import tools.jackson.databind.JsonNode;
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
    private final RunDetails details;
    private final ClarificationService clarification;

    public RunController(RunService runService, RunDetails details, ClarificationService clarification) {
        this.runService = runService;
        this.details = details;
        this.clarification = clarification;
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
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "415", description = "The request content type is not application/json.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @PostMapping
    public ResponseEntity<RunCreatedResponse> createRun(@Valid @RequestBody RunRequest request) {
        Run run = runService.createRun(request.requirement());
        return ResponseEntity.created(URI.create(RUNS_PATH + "/" + run.getId()))
                .body(new RunCreatedResponse(run.getId()));
    }

    @Operation(summary = "Read a run", description = "Returns the stored run with the given id.")
    @ApiResponse(responseCode = "200", description = "The run.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RunResponse.class)))
    @ApiResponse(responseCode = "400", description = "The id is not a UUID.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "404", description = "No run has this id.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @GetMapping("/{id}")
    public RunResponse getRun(@PathVariable UUID id) {
        Run run = runService.getRun(id);
        return RunResponse.from(run, details.stages(id), details.artifacts(id), details.verification(id),
                run.getStatus() == RunStatus.WAITING_FOR_INPUT ? clarification.awaiting(id) : null);
    }

    @Operation(summary = "Read an artifact",
            description = "Returns the content of one stored artifact of the run, as JSON.")
    @ApiResponse(responseCode = "200", description = "The artifact content.",
            content = @Content(mediaType = "application/json"))
    @ApiResponse(responseCode = "404", description = "No such run, kind or version.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @GetMapping("/{id}/artifacts/{kind}/{version}")
    public JsonNode getArtifact(@PathVariable UUID id, @PathVariable String kind, @PathVariable int version) {
        runService.getRun(id);
        return details.artifact(id, kind, version).content();
    }

    @Operation(summary = "Read the lineage of a run", description = "Returns the run's artifacts with what each was "
            + "built from, the commit and the verification outcome when they exist, and the files of the latest "
            + "development artifact with the criteria it says they serve. Built only from stored artifacts.")
    @ApiResponse(responseCode = "200", description = "The lineage; its lists are empty before the run has stored an artifact.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = LineageResponse.class)))
    @ApiResponse(responseCode = "400", description = "The id is not a UUID.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "404", description = "No run has this id.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @GetMapping("/{id}/lineage")
    public LineageResponse getLineage(@PathVariable UUID id) {
        runService.getRun(id);
        return details.lineage(id);
    }

    @Operation(summary = "Read requirements clarification", description = "Returns the clarification questions, "
            + "with their artifact version, OPEN or ANSWERED status, and saved answers.")
    @ApiResponse(responseCode = "200", description = "The questions, or an empty array before any exist.")
    @ApiResponse(responseCode = "404", description = "Run not found.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @GetMapping("/{id}/questions")
    public ClarificationService.Questions getQuestions(@PathVariable UUID id) {
        runService.getRun(id);
        return clarification.questions(id);
    }

    @Operation(summary = "Answer the complete clarification batch", description = "Exactly one answer per "
            + "question is required. Queues requirements processing again.")
    @ApiResponse(responseCode = "202", description = "Replies saved; processing is queued and continues automatically.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReplyAccepted.class)))
    @ApiResponse(responseCode = "400", description = "Incomplete or invalid answers.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "409", description = "Run is not waiting for this batch.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "404", description = "Run not found.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @PostMapping("/{id}/replies")
    public ResponseEntity<ReplyAccepted> reply(@PathVariable UUID id, @RequestBody ClarificationService.Replies replies) {
        clarification.reply(id, replies);
        return ResponseEntity.accepted().location(URI.create(RUNS_PATH + "/" + id))
                .body(new ReplyAccepted(id, "Answers submitted."));
    }

    public record ReplyAccepted(UUID runId, String message) { }
}
