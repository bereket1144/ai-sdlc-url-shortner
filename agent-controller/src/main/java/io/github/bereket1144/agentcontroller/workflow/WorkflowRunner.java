package io.github.bereket1144.agentcontroller.workflow;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);

    private final CompiledGraph<AgentState> graph;

    public WorkflowRunner() throws GraphStateException {
        // Compile the route once; each run supplies its own input state.
        this.graph = new StateGraph<>(AgentState::new)
                .addNode("initialize", node_async(this::initialize))
                .addEdge(START, "initialize")
                .addEdge("initialize", END)
                .compile();
    }

    public AgentState run(UUID runId, String requirement) {
        // Fail fast with a clear message; Map.of would fail with a bare NullPointerException.
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(requirement, "requirement must not be null");
        Map<String, Object> input = Map.of(
                "runId", runId,
                "requirement", requirement);

        AgentState result = graph.invoke(input)
                .orElseThrow(() -> new IllegalStateException("Workflow returned no state"));
        log.info("event=workflow_executed runId={}", runId);
        return result;
    }

    private Map<String, Object> initialize(AgentState state) {
        UUID runId = state.<UUID>value("runId").orElseThrow();
        String requirement = state.<String>value("requirement").orElseThrow();

        // A deterministic result that proves this node read both inputs.
        return Map.of("receipt", runId + ":" + requirement);
    }
}
