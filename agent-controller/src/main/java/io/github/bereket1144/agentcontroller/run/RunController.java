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
    private final RunCreation creation;
    private final RunDetails details;
    private final ClarificationService clarification;
    private final RunRetry retries;
    private final ApprovalService approvals;

    public RunController(RunService runService, RunCreation creation, RunDetails details,
                         ClarificationService clarification, RunRetry retries, ApprovalService approvals) {
        this.runService = runService;
        this.creation = creation;
        this.details = details;
        this.clarification = clarification;
        this.retries = retries;
        this.approvals = approvals;
    }

    // The method returns a ResponseEntity, so the library cannot see the 201 status or the body type on its own.
    @Operation(summary = "Create a run", description = "Stores a new run for the given requirement text. With no kind "
            + "it is a GREENFIELD run. A BROWNFIELD run changes the result of an earlier run, which baseRunId names: "
            + "that run must exist, have SUCCEEDED and have a stored commit.")
    @ApiResponse(responseCode = "201", description = "The run was created; its address is in the Location header.",
            headers = @Header(name = "Location", description = "Address of the new run, /v1/runs/{id}.",
                    schema = @Schema(type = "string")),
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RunCreatedResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is missing, is not valid JSON, or fails validation (the problem lists the "
                    + "offending fields in 'errors'); or the kind and the base run do not fit: a kind that is not "
                    + "GREENFIELD or BROWNFIELD, a baseRunId that is not a UUID, a BROWNFIELD run without a baseRunId, "
                    + "a GREENFIELD run with one, or a base run that does not exist, has not SUCCEEDED or has no stored "
                    + "commit. Nothing is stored.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "415", description = "The request content type is not application/json.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @PostMapping
    public ResponseEntity<RunCreatedResponse> createRun(@Valid @RequestBody RunRequest request) {
        Run run = creation.create(request);
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
        // A waiting run waits either for answers or for a decision; each lookup finds nothing for the other.
        boolean waiting = run.getStatus() == RunStatus.WAITING_FOR_INPUT;
        return RunResponse.from(run, details.stages(id), details.artifacts(id), details.verification(id),
                waiting ? clarification.awaiting(id) : null, waiting ? approvals.pending(id) : null);
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

    @Operation(summary = "Approve or reject the result of a stage", description = "Decides about the result that a run "
            + "waits for: the approval field of the run says which. APPROVE queues the run, and it continues at the next "
            + "stage with the approved version. REJECT, which needs a comment, queues the same stage to run again with "
            + "the comment given to the agent; its next result waits for a decision again. After "
            + "agent.approval.max-rejections rejections of one stage (3 unless set) the run ends FAILED with the reason "
            + "REJECTED. Each version has one decision. A refused request changes nothing.")
    @ApiResponse(responseCode = "202", description = "The decision is stored and the run is queued, or ended.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DecisionAccepted.class)))
    @ApiResponse(responseCode = "400", description = "The body is missing or invalid: no decision or an unknown one, "
            + "a blank approver or one over 100 characters, a comment over 5,000 characters, or no comment for REJECT.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "404", description = "No run has this id.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "409", description = "The run does not wait for a decision, or the version has "
            + "been decided already.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @PostMapping("/{id}/decisions")
    public ResponseEntity<DecisionAccepted> decide(@PathVariable UUID id,
                                                   @Valid @RequestBody ApprovalService.DecisionRequest request) {
        approvals.decide(id, request);
        return ResponseEntity.accepted().location(URI.create(RUNS_PATH + "/" + id))
                .body(new DecisionAccepted(id, "Decision recorded."));
    }

    @Operation(summary = "Retry a failed run", description = "Queues a FAILED run again. The first retry resumes at the "
            + "earliest stage that is not finished, with the stages before it left as they are; a later retry starts "
            + "from the beginning and reuses the stored clarification answers. A run has at most "
            + "agent.run.max-attempts attempts in all (3 unless set). The failure reason is cleared and is set again "
            + "if the attempt fails. A refused request changes nothing.")
    @ApiResponse(responseCode = "202", description = "The run is queued again and continues automatically; this is "
            + "its attempt number.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RetryAccepted.class)))
    @ApiResponse(responseCode = "400", description = "The id is not a UUID.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "404", description = "No run has this id.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @ApiResponse(responseCode = "409", description = "The run is not FAILED, or it has no attempts left.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemBody.class)))
    @PostMapping("/{id}/retry")
    public ResponseEntity<RetryAccepted> retry(@PathVariable UUID id) {
        Run run = retries.retry(id);
        return ResponseEntity.accepted().location(URI.create(RUNS_PATH + "/" + id))
                .body(new RetryAccepted(id, run.getAttempt()));
    }

    public record ReplyAccepted(UUID runId, String message) { }

    public record DecisionAccepted(UUID runId, String message) { }

    public record RetryAccepted(UUID id, int attempt) { }
}
