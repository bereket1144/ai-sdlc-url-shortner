package io.github.bereket1144.agentcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.StateGraph.END;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.bereket1144.agentcontroller.checkpoint.CheckpointSaverFactory;

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
 *
 * <p>The tables come from the Flyway migrations and the saver is the production one
 * ({@link CheckpointSaverFactory}), so everything here runs on the storage the platform uses.
 */
@Testcontainers
class ApprovalPauseCheckpointTest {

    private static final String GATE = ApprovalGraph.GATE;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));

    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Test
    @DisplayName("AC-AGT-34.4 a run stops at the gate and its paused checkpoint is readable from PostgreSQL")
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
    @DisplayName("AC-AGT-34.4 a paused run resumes on a fresh graph and saver, and each later task runs once")
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

    @Test
    @DisplayName("AC-AGT-34.2 a checkpoint is stored as JSON text that PostgreSQL itself reads back as text")
    void theStoredStateIsJsonText() throws Exception {
        String runId = "run-" + UUID.randomUUID();

        buildGraph(newSaver()).invoke(Map.of(), RunnableConfig.builder().threadId(runId).build());

        // The query behind stateText() is the one a reviewer would run. It fails on Java serialised bytes.
        String text = checkpointRowsFor(runId).getFirst().stateText();
        assertThat(text).startsWith("{").endsWith("}").contains("\"draft\":\"draft-v1\"");
    }

    @Test
    @DisplayName("AC-AGT-34.3 a stored checkpoint with a field this code does not know, and without one it writes, still reads")
    void aCheckpointWrittenByOtherCodeStillReads() throws Exception {
        String runId = "run-" + UUID.randomUUID();
        RunnableConfig config = RunnableConfig.builder().threadId(runId).build();
        buildGraph(newSaver()).invoke(Map.of(), config);

        // Store the paused state as another version of the code could have written it: it has a field this
        // code has never heard of ("addedByAnotherVersion") and lacks one this code writes ("draft").
        replaceStoredState(runId, "{\"addedByAnotherVersion\":\"x\"}");

        var restored = buildGraph(newSaver()).getState(config);
        assertThat(restored.next()).isEqualTo(GATE);
        assertThat(restored.state().<String>value("addedByAnotherVersion")).contains("x");
        assertThat(restored.state().<String>value("draft").orElse("no draft yet")).isEqualTo("no draft yet");
    }

    private CompiledGraph<AgentState> buildGraph(PostgresSaver saver) throws GraphStateException {
        return ApprovalGraph.build(saver, executed::add);
    }

    private static PostgresSaver newSaver() throws SQLException {
        return CheckpointSaverFactory.create(POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Reads the checkpoint rows straight from the tables, newest first, without going through the saver. */
    private static List<Row> checkpointRowsFor(String threadName) throws SQLException {
        String sql = """
                SELECT c.node_id, c.next_node_id, c.state_data->>'binaryPayload' IS NOT NULL AS stored,
                       convert_from(decode(c.state_data->>'binaryPayload', 'base64'), 'UTF8') AS state_text
                FROM LG4JCheckpoint c
                JOIN LG4JThread t ON t.thread_id = c.thread_id
                WHERE t.thread_name = ?
                ORDER BY c.saved_at DESC
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, threadName);
            try (ResultSet rows = statement.executeQuery()) {
                List<Row> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new Row(rows.getString("node_id"), rows.getString("next_node_id"),
                            rows.getBoolean("stored"), rows.getString("state_text")));
                }
                return result;
            }
        }
    }

    /**
     * Overwrites the state text of the checkpoint waiting at the gate, as other code could have stored it. The
     * base64 is made here because PostgreSQL's own base64 adds line breaks, which the saver cannot read.
     */
    private static void replaceStoredState(String threadName, String stateJson) throws SQLException {
        String payload = Base64.getEncoder().encodeToString(stateJson.getBytes(StandardCharsets.UTF_8));
        String sql = """
                UPDATE LG4JCheckpoint
                SET state_data = ?::jsonb
                WHERE next_node_id = ?
                  AND thread_id = (SELECT thread_id FROM LG4JThread WHERE thread_name = ?)
                """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "{\"binaryPayload\": \"" + payload + "\"}");
            statement.setString(2, GATE);
            statement.setString(3, threadName);
            assertThat(statement.executeUpdate()).as("checkpoints rewritten").isEqualTo(1);
        }
    }

    private record Row(String nodeId, String nextNodeId, boolean stateStored, String stateText) {}
}
