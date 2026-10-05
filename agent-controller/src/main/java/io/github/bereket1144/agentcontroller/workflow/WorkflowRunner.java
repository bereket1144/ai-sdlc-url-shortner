package io.github.bereket1144.agentcontroller.workflow;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.document.RunDocuments;
import io.github.bereket1144.agentcontroller.run.ApprovalService;
import io.github.bereket1144.agentcontroller.run.RunFailureReason;
import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);
    /** How many more times a stage is attempted when {@code agent.stage.max-retries} is not set. */
    static final int DEFAULT_MAX_RETRIES = 2;
    /** The reasons after which a stage is attempted again; any other failure ends the run at once. */
    private static final Set<RunFailureReason> RETRYABLE = EnumSet.of(RunFailureReason.OUTPUT_INVALID,
            RunFailureReason.MODEL_UNAVAILABLE, RunFailureReason.VERIFICATION_FAILED, RunFailureReason.UNEXPECTED);
    /** The graph state key that tells the entry edge where an attempt starts; a fresh run has none. */
    static final String START_AT = "startAt";
    /** The graph state key of the run that a BROWNFIELD run changes; a run that changes none has no such key. */
    static final String BASE_RUN_ID = "baseRunId";
    private static final String INITIALIZE = "initialize";
    /** A node that starts QA, review and documentation together, for an attempt that resumes at one of them. */
    private static final String RESUME_GROUP = "resumeGroup";
    /** The graph state key that marks an attempt that stopped to wait for answers or for a reviewer's decision. */
    private static final String WAITING = "waitingForInput";
    /** The answer of the edge after a stage that is held for a decision: the attempt ends there. */
    private static final String WAIT_FOR_DECISION = "waitForDecision";

    private final CompiledGraph<AgentState> graph;
    private final Optional<StageProgress> progress;
    private final Optional<RequirementsAgentNode> requirementsAgent;
    private final Optional<RunResume> resume;
    private final Optional<ApprovalService> approvals;
    private final int maxRetries;

    /** Every stage on its dummy node. */
    public WorkflowRunner(DummySdlcNodes nodes) throws GraphStateException {
        this(nodes, Optional.empty(), Optional.empty());
    }

    /** The requirements agent replaces the dummy requirements stage when its setting enabled it. */
    public WorkflowRunner(DummySdlcNodes nodes, Optional<RequirementsAgentNode> requirementsAgent)
            throws GraphStateException {
        this(nodes, requirementsAgent, Optional.empty());
    }

    /**
     * A stage whose setting enabled its agent runs on that agent; every other stage stays on its dummy node. The agent
     * stages are optional only because a test context that loads this class alone does not hold them.
     */
    public WorkflowRunner(DummySdlcNodes nodes, Optional<RequirementsAgentNode> requirementsAgent,
                          Optional<AgentStages> agentStages) throws GraphStateException {
        this(nodes, requirementsAgent, agentStages, Optional.empty());
    }

    /** When the development agent is on, its stage also commits the change set and has it built and tested. */
    public WorkflowRunner(DummySdlcNodes nodes, Optional<RequirementsAgentNode> requirementsAgent,
                          Optional<AgentStages> agentStages, Optional<DevelopmentFinish> developmentFinish)
            throws GraphStateException {
        this(nodes, requirementsAgent, agentStages, developmentFinish, Optional.empty(), Optional.empty());
    }

    /** A stage is attempted again the default number of times. */
    public WorkflowRunner(DummySdlcNodes nodes, Optional<RequirementsAgentNode> requirementsAgent,
                          Optional<AgentStages> agentStages, Optional<DevelopmentFinish> developmentFinish,
                          Optional<StageProgress> progress, Optional<RunDocuments> runDocuments)
            throws GraphStateException {
        this(nodes, requirementsAgent, agentStages, developmentFinish, progress, runDocuments, DEFAULT_MAX_RETRIES);
    }

    /**
     * With a progress recorder, every stage writes when it starts, when it finishes and when it fails for good. A
     * stage that fails for a reason that can pass on another try is attempted again, up to {@code maxRetries} more
     * times. With the run documents, a last step after the join writes them into the run's repository; they exist only
     * when the development agent is on.
     *
     * @param maxRetries {@code agent.stage.max-retries}: how many more attempts a failed stage gets; 0 is none
     */
    public WorkflowRunner(DummySdlcNodes nodes, Optional<RequirementsAgentNode> requirementsAgent,
                          Optional<AgentStages> agentStages, Optional<DevelopmentFinish> developmentFinish,
                          Optional<StageProgress> progress, Optional<RunDocuments> runDocuments, int maxRetries)
            throws GraphStateException {
        this(nodes, requirementsAgent, agentStages, developmentFinish, progress, runDocuments, maxRetries,
                Optional.empty());
    }

    /**
     * As above. With the run resume, the runner can also attempt a failed run again from where an earlier attempt
     * stopped ({@link #retry}); it is optional for the same reason as the agent stages.
     */
    public WorkflowRunner(DummySdlcNodes nodes, Optional<RequirementsAgentNode> requirementsAgent,
                          Optional<AgentStages> agentStages, Optional<DevelopmentFinish> developmentFinish,
                          Optional<StageProgress> progress, Optional<RunDocuments> runDocuments, int maxRetries,
                          Optional<RunResume> resume)
            throws GraphStateException {
        this(nodes, requirementsAgent, agentStages, developmentFinish, progress, runDocuments, maxRetries, resume,
                Optional.empty());
    }

    /**
     * As above. With the approvals, a stage that {@code agent.approval.required-for} lists ends the attempt after it has
     * stored its artifact, and waits for a reviewer's decision; when the reviewer rejected it before, the agent is given
     * the comments. It is optional for the same reason as the agent stages.
     */
    @Autowired
    public WorkflowRunner(DummySdlcNodes nodes, Optional<RequirementsAgentNode> requirementsAgent,
                          Optional<AgentStages> agentStages, Optional<DevelopmentFinish> developmentFinish,
                          Optional<StageProgress> progress, Optional<RunDocuments> runDocuments,
                          @Value("${agent.stage.max-retries:" + DEFAULT_MAX_RETRIES + "}") int maxRetries,
                          Optional<RunResume> resume, Optional<ApprovalService> approvals)
            throws GraphStateException {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("agent.stage.max-retries must not be negative");
        }
        Map<String, Function<AgentState, Map<String, Object>>> agents = new HashMap<>();
        requirementsAgent.ifPresent(node -> agents.put("requirements", node::requirements));
        agentStages.ifPresent(stages -> stages.nodes().forEach((role, node) -> agents.put(role,
                state -> node.execute(state, reviewerFeedback(approvals, state, role), List.of()))));
        developmentFinish.ifPresent(finish -> {
            AgentStageNode development = agentStages.map(stages -> stages.nodes().get("development")).orElse(null);
            if (development != null) {
                agents.put("development", state -> finish.develop(state, development));
            }
        });
        this.progress = progress;
        this.requirementsAgent = requirementsAgent;
        this.resume = resume;
        this.approvals = approvals;
        this.maxRetries = maxRetries;
        // Compile the fixed stage route once. The run supplies its own state and branch executor.
        StateGraph<AgentState> route = new StateGraph<>(AgentState::new)
                .addNode(INITIALIZE, node_async(nodes::initialize))
                .addNode("requirements", node_async(stage(agents, "requirements", nodes::requirements)))
                .addNode("impactAnalysis", node_async(stage(agents, "impactAnalysis", nodes::impactAnalysis)))
                .addNode("design", node_async(stage(agents, "design", nodes::design)))
                .addNode("planning", node_async(stage(agents, "planning", nodes::planning)))
                .addNode("development", node_async(stage(agents, "development", nodes::development)))
                .addNode("qa", node_async(stage(agents, "qa", nodes::qa)))
                .addNode("review", node_async(stage(agents, "review", nodes::review)))
                .addNode("documentation", node_async(stage(agents, "documentation", nodes::documentation)))
                .addNode("collect", node_async(nodes::collect))
                .addNode(RESUME_GROUP, node_async(WorkflowRunner::startGroup))
                // A fresh run starts at initialize; a retry starts where its start point says.
                .addConditionalEdges(START, edge_async(this::entry), entryPoints())
                .addEdge(INITIALIZE, "requirements")
                .addConditionalEdges("requirements", edge_async(this::afterRequirements),
                        Map.of("impactAnalysis", "impactAnalysis", "design", "design"))
                .addEdge("impactAnalysis", "design")
                // A stage that a reviewer has to decide about marks the state as waiting; the attempt then ends.
                .addConditionalEdges("design", edge_async(state -> after(state, "planning")),
                        Map.of("planning", "planning", WAIT_FOR_DECISION, END))
                .addConditionalEdges("planning", edge_async(state -> after(state, "development")),
                        Map.of("development", "development", WAIT_FOR_DECISION, END))
                .addEdge("development", "qa")
                .addEdge("development", "review")
                .addEdge("development", "documentation")
                .addEdge(RESUME_GROUP, "qa")
                .addEdge(RESUME_GROUP, "review")
                .addEdge(RESUME_GROUP, "documentation")
                .addEdge("qa", "collect")
                .addEdge("review", "collect")
                .addEdge("documentation", "collect");
        if (runDocuments.isPresent()) {
            // The step is after the join, so a run that failed on the way to it writes none of the documents.
            route.addNode("documents", node_async(documents(runDocuments.get())))
                    .addEdge("collect", "documents")
                    .addEdge("documents", END);
        } else {
            route.addEdge("collect", END);
        }
        this.graph = route.compile();
    }

    private static NodeAction<AgentState> documents(RunDocuments runDocuments) {
        return state -> {
            runDocuments.write(state.<UUID>value("runId")
                    .orElseThrow(() -> new IllegalStateException("Missing workflow state: runId")));
            return Map.of();
        };
    }

    private NodeAction<AgentState> stage(Map<String, Function<AgentState, Map<String, Object>>> agents, String role,
                                         Function<AgentState, Map<String, Object>> dummy) {
        Function<AgentState, Map<String, Object>> agent = agents.get(role);
        log.info("event=workflow_stage_selected stage={} node={}", role, agent == null ? "dummy" : "agent");
        Function<AgentState, Map<String, Object>> action = agent == null ? dummy : agent;
        if (role.equals("requirements") && agent != null) {
            // The attempts and the progress of the real requirements stage belong to the persisted clarification
            // preflight in run(); here the graph only picks up the reference that it left.
            return action::apply;
        }
        return state -> {
            UUID runId = state.<UUID>value("runId").orElseThrow();
            Map<String, Object> update = attempts(runId, role, action, state);
            progress.ifPresent(recorder -> recorder.finished(runId, role));
            return waitForDecision(runId, role, update);
        };
    }

    /**
     * After a stage has stored its artifact and is finished: when a reviewer has to decide about it first, the run starts
     * to wait and the update marks the state as waiting, so that the edge after the stage ends the attempt. A stage on its
     * dummy node stores no artifact, so there is nothing to decide about.
     */
    private Map<String, Object> waitForDecision(UUID runId, String role, Map<String, Object> update) {
        if (approvals.isPresent() && update.get(role) instanceof ArtifactRef artifact
                && approvals.get().waitForDecision(runId, role, artifact)) {
            Map<String, Object> waiting = new HashMap<>(update);
            waiting.put(WAITING, true);
            return waiting;
        }
        return update;
    }

    /** The comments of a reviewer who rejected earlier results of this stage, as text for the task; empty when none. */
    private static String reviewerFeedback(Optional<ApprovalService> approvals, AgentState state, String role) {
        UUID runId = state.<UUID>value("runId").orElseThrow();
        return approvals.map(service -> AgentStageNode.reviewerFeedback(service.rejectionComments(runId, role)))
                .orElse("");
    }

    /** The node after a stage that may be held for a decision: the next stage, or the end of an attempt that waits. */
    private static String after(AgentState state, String next) {
        return state.<Boolean>value(WAITING).orElse(false) ? WAIT_FOR_DECISION : next;
    }

    /**
     * Runs a stage until it returns or fails for good, recording RUNNING at the start of every attempt and FAILED when
     * the stage is given up. A failure is attempted again when its reason is retryable and fewer than
     * {@code maxRetries} retries have been used. An error, or an interrupted thread, is never attempted again: an
     * interrupted stage was cancelled, as when a parallel branch failed, and writes nothing more. The last failure is
     * rethrown as it is, so the run ends with its reason.
     */
    private Map<String, Object> attempts(UUID runId, String stage, Function<AgentState, Map<String, Object>> action,
                                         AgentState state) {
        for (int attempt = 1; ; attempt++) {
            progress.ifPresent(recorder -> recorder.started(runId, stage));
            try {
                return action.apply(state);
            } catch (RuntimeException | Error failure) {
                RunFailureReason reason = RunFailureReason.from(failure);
                boolean interrupted = Thread.currentThread().isInterrupted();
                if (attempt <= maxRetries && RETRYABLE.contains(reason) && !(failure instanceof Error)
                        && !interrupted) {
                    // The run, the stage, the attempt and the reason code only: never the message of the failure.
                    log.warn("event=stage_retry runId={} stage={} attempt={} reason={}", runId, stage, attempt, reason);
                    continue;
                }
                log.warn("event=stage_failed runId={} stage={} attempt={} reason={}", runId, stage, attempt, reason);
                if (!interrupted) {
                    progress.ifPresent(recorder -> recorder.failed(runId, stage));
                }
                throw failure;
            }
        }
    }

    /** The first attempt of a run that changes no earlier run: the whole graph, from the start. */
    public AgentState run(UUID runId, String requirement, RunKind kind) {
        return run(runId, requirement, kind, null);
    }

    /**
     * The first attempt of a run: the whole graph, from the start.
     *
     * @param baseRunId the run whose workspace a BROWNFIELD run starts from; null for a run that changes no earlier run
     */
    public AgentState run(UUID runId, String requirement, RunKind kind, UUID baseRunId) {
        requireInputs(runId, requirement, kind);
        Map<String, Object> input = new HashMap<>(Map.of(
                "runId", runId,
                "requirement", requirement,
                "kind", kind));
        withBase(input, baseRunId);
        return execute(runId, kind, input, true);
    }

    /**
     * A later attempt of a failed run. From {@link RunResume#START} it is the whole graph again, with every stage shown
     * as not started; the requirements stage reuses the clarification answers that are stored. From a stage it starts
     * the graph at that stage with the state rebuilt from what the earlier attempts stored, and the stages before it do
     * not run again; the group of QA, review and documentation starts together. Every stage keeps its own retries.
     *
     * @param startAt {@link RunResume#START} or the stage the attempt starts at, as {@link RunResume#startPoint} chose it
     */
    public AgentState retry(UUID runId, String requirement, RunKind kind, String startAt) {
        return retry(runId, requirement, kind, null, startAt);
    }

    /** As above, for a run that changes the earlier run {@code baseRunId}; the base is null when it changes none. */
    public AgentState retry(UUID runId, String requirement, RunKind kind, UUID baseRunId, String startAt) {
        requireInputs(runId, requirement, kind);
        Objects.requireNonNull(startAt, "startAt must not be null");
        RunResume resumed = resume.orElseThrow(() -> new IllegalStateException("A retry needs the run resume"));
        Map<String, Object> input = new HashMap<>(resumed.input(runId, requirement, kind, startAt));
        withBase(input, baseRunId);
        boolean fromStart = startAt.equals(RunResume.START);
        if (fromStart) {
            progress.ifPresent(recorder -> recorder.reset(runId));
        } else {
            input.put(START_AT, startAt);
        }
        log.info("event=workflow_retry runId={} kind={} startAt={}", runId, kind, startAt);
        return execute(runId, kind, input, fromStart);
    }

    private static void withBase(Map<String, Object> input, UUID baseRunId) {
        if (baseRunId != null) {
            input.put(BASE_RUN_ID, baseRunId);
        }
    }

    private static void requireInputs(UUID runId, String requirement, RunKind kind) {
        // Fail fast with a clear message; Map.of would fail with a bare NullPointerException.
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(requirement, "requirement must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
    }

    /** @param withRequirements whether the requirements stage runs first; an attempt that starts later has its result */
    private AgentState execute(UUID runId, RunKind kind, Map<String, Object> input, boolean withRequirements) {
        if (withRequirements && requirementsAgent.isPresent()) {
            Map<String, Object> prepared = attempts(runId, "requirements", requirementsAgent.get()::prepare,
                    new AgentState(input));
            input.putAll(prepared);
            if (Boolean.TRUE.equals(prepared.get(WAITING))) {
                log.info("event=workflow_waiting runId={} stage=requirements", runId);
                return new AgentState(input);
            }
            progress.ifPresent(recorder -> recorder.finished(runId, "requirements"));
        }

        // LangGraph4j needs an executor on the fan-out node for these branches to overlap.
        try (ExecutorService branchExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            RunnableConfig config = RunnableConfig.builder()
                    .addParallelNodeExecutor("development", branchExecutor)
                    .addParallelNodeExecutor(RESUME_GROUP, branchExecutor)
                    .build();
            Optional<AgentState> finalState;
            try {
                finalState = graph.invoke(input, config);
            } catch (RuntimeException | Error failure) {
                // LangGraph4j fails fast on a failed branch but leaves its siblings running, and closing the
                // executor would wait for them. Interrupt them so the failure is reported without that wait.
                // This only helps branches that honour interruption (see docs/design-notes.md).
                branchExecutor.shutdownNow();
                log.warn("event=workflow_failed runId={} kind={} errorType={}",
                        runId, kind, failure.getClass().getName());
                throw failure;
            }
            AgentState result = finalState
                    .orElseThrow(() -> new IllegalStateException("Workflow returned no state"));
            log.info("event=workflow_executed runId={} kind={}", runId, kind);
            return result;
        }
    }

    /** The node the graph starts at: the start point of a retry, and for a fresh run the first node. */
    private String entry(AgentState state) {
        return state.<String>value(START_AT).orElse(INITIALIZE);
    }

    /** Every start point and the node it starts at; QA, review and documentation start together, as one group. */
    private static Map<String, String> entryPoints() {
        return Map.of(INITIALIZE, INITIALIZE, "impactAnalysis", "impactAnalysis", "design", "design",
                "planning", "planning", "development", "development",
                "qa", RESUME_GROUP, "review", RESUME_GROUP, "documentation", RESUME_GROUP);
    }

    /** It only gives the group its three edges, so that the three stages start side by side. */
    private static Map<String, Object> startGroup(AgentState state) {
        return Map.of();
    }

    private String afterRequirements(AgentState state) {
        RunKind kind = state.<RunKind>value("kind").orElseThrow();
        UUID runId = state.<UUID>value("runId").orElseThrow();
        log.info("event=workflow_route_selected runId={} kind={}", runId, kind);
        return kind == RunKind.BROWNFIELD ? "impactAnalysis" : "design";
    }
}
