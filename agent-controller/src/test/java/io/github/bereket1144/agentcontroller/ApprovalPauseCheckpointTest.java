package io.github.bereket1144.agentcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the real LangGraph4j engine against a real PostgreSQL (the version Compose uses) with fake
 * tasks and no model calls. It checks the two properties the approval gate will rely on: the run
 * stops at the gate without running anything past it, and the paused state is in the database,
 * where something that never saw the run can read it back.
 *
 * <p>The graph is {@code draft -> approval-gate -> publish}, paused before {@code approval-gate}.
 * Resuming after the approval is a separate step.
 */
@Testcontainers
class ApprovalPauseCheckpointTest {

    private static final String GATE = "approval-gate";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    @Test
    void runStopsAtTheGateAndTheCheckpointIsReadableFromPostgres() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        RunnableConfig config = RunnableConfig.builder().threadId(runId).build();

        buildGraph(newSaver()).invoke(Map.of(), config);

        // The run stopped at the gate: the task before it ran, the gate and the task after did not.
        assertThat(executed).containsExactly("draft");

        // The paused state is in the database itself, not only in memory.
        List<Row> saved = checkpointRowsFor(runId);
        assertThat(saved).isNotEmpty();
        assertThat(saved.getFirst().nextNodeId()).isEqualTo(GATE);
        assertThat(saved.getFirst().stateStored()).isTrue();

        // A saver and graph that never ran anything read the same paused run back from PostgreSQL.
        var restored = buildGraph(newSaver()).getState(config);
        assertThat(restored.next()).isEqualTo(GATE);
        assertThat(restored.state().<String>value("draft")).contains("draft-v1");
        assertThat(executed).as("reading the checkpoint ran nothing").containsExactly("draft");
    }

    private CompiledGraph<AgentState> buildGraph(PostgresSaver saver) throws GraphStateException {
        return new StateGraph<>(AgentState::new)
                .addNode("draft", node_async(state -> {
                    executed.add("draft");
                    return Map.of("draft", "draft-v1");
                }))
                .addNode(GATE, node_async(state -> {
                    executed.add(GATE);
                    return Map.of();
                }))
                .addNode("publish", node_async(state -> {
                    executed.add("publish");
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

    private static PostgresSaver newSaver() throws SQLException {
        return PostgresSaver.builder()
                .host(POSTGRES.getHost())
                .port(POSTGRES.getMappedPort(5432))
                .database(POSTGRES.getDatabaseName())
                .user(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .stateSerializer(new ObjectStreamStateSerializer<>(AgentState::new))
                .createTables(true)
                .build();
    }

    /** Reads the checkpoint rows straight from the tables, newest first, without going through the saver. */
    private static List<Row> checkpointRowsFor(String threadName) throws SQLException {
        String sql = """
                SELECT c.node_id, c.next_node_id, c.state_data->>'binaryPayload' IS NOT NULL AS stored
                FROM LG4JCheckpoint c
                JOIN LG4JThread t ON t.thread_id = c.thread_id
                WHERE t.thread_name = ?
                ORDER BY c.saved_at DESC
                """;
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, threadName);
            try (ResultSet rows = statement.executeQuery()) {
                List<Row> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new Row(rows.getString("node_id"), rows.getString("next_node_id"), rows.getBoolean("stored")));
                }
                return result;
            }
        }
    }

    private record Row(String nodeId, String nextNodeId, boolean stateStored) {}
}
