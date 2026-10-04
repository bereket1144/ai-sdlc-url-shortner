package io.github.bereket1144.agentcontroller.workflow;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);

    private final CompiledGraph<AgentState> graph;

    public WorkflowRunner(DummySdlcNodes nodes) throws GraphStateException {
        // Compile the fixed stage route once. The run supplies its own state and branch executor.
        this.graph = new StateGraph<>(AgentState::new)
                .addNode("initialize", node_async(nodes::initialize))
                .addNode("requirements", node_async(nodes::requirements))
                .addNode("impactAnalysis", node_async(nodes::impactAnalysis))
                .addNode("design", node_async(nodes::design))
                .addNode("planning", node_async(nodes::planning))
                .addNode("development", node_async(nodes::development))
                .addNode("qa", node_async(nodes::qa))
                .addNode("review", node_async(nodes::review))
                .addNode("documentation", node_async(nodes::documentation))
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
                .addEdge("documentation", "collect")
                .addEdge("collect", END)
                .compile();
    }

    public AgentState run(UUID runId, String requirement, RunKind kind) {
        // Fail fast with a clear message; Map.of would fail with a bare NullPointerException.
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(requirement, "requirement must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Map<String, Object> input = Map.of(
                "runId", runId,
                "requirement", requirement,
                "kind", kind);

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
