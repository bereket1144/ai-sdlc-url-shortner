package io.github.bereket1144.agentcontroller.workflow;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.bereket1144.agentcontroller.document.RunDocuments;
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
import org.springframework.stereotype.Component;

@Component
public class WorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);

    private final CompiledGraph<AgentState> graph;
    private final Optional<StageProgress> progress;
    private final Optional<RequirementsAgentNode> requirementsAgent;

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

    /**
     * With a progress recorder, every stage writes when it starts and when it finishes. With the run documents, a last
     * step after the join writes them into the run's repository; they exist only when the development agent is on.
     */
    @Autowired
    public WorkflowRunner(DummySdlcNodes nodes, Optional<RequirementsAgentNode> requirementsAgent,
                          Optional<AgentStages> agentStages, Optional<DevelopmentFinish> developmentFinish,
                          Optional<StageProgress> progress, Optional<RunDocuments> runDocuments)
            throws GraphStateException {
        Map<String, NodeAction<AgentState>> agents = new HashMap<>();
        requirementsAgent.ifPresent(node -> agents.put("requirements", node::requirements));
        agentStages.ifPresent(stages -> stages.nodes().forEach((role, node) -> agents.put(role, node::execute)));
        developmentFinish.ifPresent(finish -> {
            NodeAction<AgentState> development = agents.get("development");
            if (development != null) {
                agents.put("development", state -> finish.finish(state, development.apply(state)));
            }
        });
        this.progress = progress;
        this.requirementsAgent = requirementsAgent;
        // Compile the fixed stage route once. The run supplies its own state and branch executor.
        StateGraph<AgentState> route = new StateGraph<>(AgentState::new)
                .addNode("initialize", node_async(nodes::initialize))
                .addNode("requirements", node_async(stage(agents, "requirements", nodes::requirements)))
                .addNode("impactAnalysis", node_async(stage(agents, "impactAnalysis", nodes::impactAnalysis)))
                .addNode("design", node_async(stage(agents, "design", nodes::design)))
                .addNode("planning", node_async(stage(agents, "planning", nodes::planning)))
                .addNode("development", node_async(stage(agents, "development", nodes::development)))
                .addNode("qa", node_async(stage(agents, "qa", nodes::qa)))
                .addNode("review", node_async(stage(agents, "review", nodes::review)))
                .addNode("documentation", node_async(stage(agents, "documentation", nodes::documentation)))
                .addNode("collect", node_async(nodes::collect))
                .addEdge(START, "initialize")
                .addEdge("initialize", "requirements")
                .addConditionalEdges("requirements", edge_async(this::afterRequirements),
                        Map.of("impactAnalysis", "impactAnalysis", "design", "design"))
                .addEdge("impactAnalysis", "design")
                .addEdge("design", "planning")
                .addEdge("planning", "development")
                .addEdge("development", "qa")
                .addEdge("development", "review")
                .addEdge("development", "documentation")
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

    private NodeAction<AgentState> stage(Map<String, NodeAction<AgentState>> agents, String role,
                                         NodeAction<AgentState> dummy) {
        NodeAction<AgentState> agent = agents.get(role);
        log.info("event=workflow_stage_selected stage={} node={}", role, agent == null ? "dummy" : "agent");
        NodeAction<AgentState> action = agent == null ? dummy : agent;
        if (progress.isEmpty()) {
            return action;
        }
        // The stage records that it is running and, if it returns, that it finished; a failed stage stays running.
        return state -> {
            if (role.equals("requirements") && agent != null) {
                // Real requirements progress was recorded during the persisted clarification preflight.
                return action.apply(state);
            }
            UUID runId = state.<UUID>value("runId").orElseThrow();
            progress.get().started(runId, role);
            Map<String, Object> update = action.apply(state);
            progress.get().finished(runId, role);
            return update;
        };
    }

    public AgentState run(UUID runId, String requirement, RunKind kind) {
        // Fail fast with a clear message; Map.of would fail with a bare NullPointerException.
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(requirement, "requirement must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Map<String, Object> input = new HashMap<>(Map.of(
                "runId", runId,
                "requirement", requirement,
                "kind", kind));

        if (requirementsAgent.isPresent()) {
            progress.ifPresent(recorder -> recorder.started(runId, "requirements"));
            Map<String, Object> prepared = requirementsAgent.get().prepare(new AgentState(input));
            input.putAll(prepared);
            if (Boolean.TRUE.equals(prepared.get("waitingForInput"))) {
                log.info("event=workflow_waiting runId={} stage=requirements", runId);
                return new AgentState(input);
            }
            progress.ifPresent(recorder -> recorder.finished(runId, "requirements"));
        }

        // LangGraph4j needs an executor on the fan-out node for these branches to overlap.
        try (ExecutorService branchExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            RunnableConfig config = RunnableConfig.builder()
                    .addParallelNodeExecutor("development", branchExecutor)
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

    private String afterRequirements(AgentState state) {
        RunKind kind = state.<RunKind>value("kind").orElseThrow();
        UUID runId = state.<UUID>value("runId").orElseThrow();
        log.info("event=workflow_route_selected runId={} kind={}", runId, kind);
        return kind == RunKind.BROWNFIELD ? "impactAnalysis" : "design";
    }
}
