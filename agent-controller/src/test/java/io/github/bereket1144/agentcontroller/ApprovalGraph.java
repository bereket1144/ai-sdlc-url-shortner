package io.github.bereket1144.agentcontroller;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.sql.SQLException;
import java.util.Map;
import java.util.function.Consumer;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;

/**
 * The graph the approval tests run: {@code draft -> approval-gate -> publish}, paused before the
 * gate, with fake tasks. It is a fixture so the tests, and any JVM they start, build the same graph.
 */
final class ApprovalGraph {

    static final String GATE = "approval-gate";

    private ApprovalGraph() {}

    /** Builds the graph; {@code onExecuted} is told the name of each task as it runs. */
    static CompiledGraph<AgentState> build(PostgresSaver saver, Consumer<String> onExecuted)
            throws GraphStateException {
        return new StateGraph<>(AgentState::new)
                .addNode("draft", node_async(state -> {
                    onExecuted.accept("draft");
                    return Map.of("draft", "draft-v1");
                }))
                .addNode(GATE, node_async(state -> {
                    if (!state.<Boolean>value("approved").orElse(false)) {
                        throw new IllegalStateException("run reached the gate without an approval");
                    }
                    onExecuted.accept(GATE);
                    return Map.of();
                }))
                .addNode("publish", node_async(state -> {
                    onExecuted.accept("publish");
                    return Map.of();
                }))
                .addEdge(START, "draft")
                .addEdge("draft", GATE)
                .addEdge(GATE, "publish")
                .addEdge("publish", END)
                .compile(CompileConfig.builder()
                        .checkpointSaver(saver)
                        .interruptBefore(GATE)
                        .build());
    }

    /** A new saver on the given PostgreSQL; it creates the checkpoint tables if they are missing. */
    static PostgresSaver saver(String host, int port, String database, String user, String password)
            throws SQLException {
        return PostgresSaver.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .password(password)
                .stateSerializer(new ObjectStreamStateSerializer<>(AgentState::new))
                .createTables(true)
                .build();
    }
}
