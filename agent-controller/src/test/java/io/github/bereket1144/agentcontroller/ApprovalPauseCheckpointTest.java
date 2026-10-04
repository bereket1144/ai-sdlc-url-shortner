package io.github.bereket1144.agentcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.StateGraph.END;

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

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the real LangGraph4j engine against a real PostgreSQL (the version Compose uses) with fake
 * tasks and no model calls. It checks what the approval gate will rely on: the run stops at the
 * gate without running anything past it, the paused state is in the database where something that
 * never saw the run can read it back, and a paused run is resumed by a new graph and saver.
 *
 * <p>"New" here means new objects in the same JVM. That shows the state lives in PostgreSQL and not
 * in the graph or saver; it does not show recovery after a killed process.
 *
 * <p>The graph is {@code draft -> approval-gate -> publish}, paused before {@code approval-gate}.
 * The gate only passes when the saved state says {@code approved}.
 */
@Testcontainers
class ApprovalPauseCheckpointTest {

    private static final String GATE = ApprovalGraph.GATE;

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

    @Test
    void aPausedRunResumesOnAFreshGraphAndSaverAndEachLaterTaskRunsOnce() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        RunnableConfig config = RunnableConfig.builder().threadId(runId).build();

        // Run 1: goes up to the gate and stops.
        buildGraph(newSaver()).invoke(Map.of(), config);
        assertThat(executed).containsExactly("draft");

        // Run 2: a new graph and saver, in the same JVM, that share nothing with run 1 except PostgreSQL.
        // The approval is saved into the run's state, then the run is resumed from that checkpoint.
        CompiledGraph<AgentState> freshGraph = buildGraph(newSaver());
        RunnableConfig approved = freshGraph.updateState(config, Map.of("approved", true));
        AgentState finalState = freshGraph.invoke(GraphInput.resume(), approved).orElseThrow();

        // The task before the gate did not run again; the gate and the task after it ran once each.
        assertThat(executed).containsExactly("draft", GATE, "publish");
        assertThat(finalState.<String>value("draft")).contains("draft-v1");
        assertThat(finalState.<Boolean>value("approved")).contains(true);

        // The run is finished, and yet another new graph and saver see it as finished, not paused.
        assertThat(buildGraph(newSaver()).getState(config).next()).isEqualTo(END);
    }

    private CompiledGraph<AgentState> buildGraph(PostgresSaver saver) throws GraphStateException {
        return ApprovalGraph.build(saver, executed::add);
    }

    private static PostgresSaver newSaver() throws SQLException {
        return ApprovalGraph.saver(POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
