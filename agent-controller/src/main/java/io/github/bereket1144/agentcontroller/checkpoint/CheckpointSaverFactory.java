package io.github.bereket1144.agentcontroller.checkpoint;

import java.sql.SQLException;

import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the checkpoint saver the controller uses (AGT-34). Every caller gets the same two choices:
 * <ul>
 *   <li>The saver never creates tables. Migration {@code V5__create_checkpoint_tables.sql} owns them.
 *   <li>The graph state is stored as JSON text, not as Java serialised bytes.
 * </ul>
 */
public final class CheckpointSaverFactory {

    private static final Logger log = LoggerFactory.getLogger(CheckpointSaverFactory.class);

    private CheckpointSaverFactory() {}

    /**
     * A saver on the given PostgreSQL. The checkpoint tables must already exist: the saver does not create them.
     * The library checks the connection settings and throws {@link IllegalArgumentException} or
     * {@link NullPointerException} for a blank or missing one, and {@link SQLException} if the database cannot be reached.
     */
    public static PostgresSaver create(String host, int port, String database, String user, String password)
            throws SQLException {
        PostgresSaver saver = PostgresSaver.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .password(password)
                .stateSerializer(new JacksonStateSerializer<AgentState>(AgentState::new) {})
                // Without this flag the saver wraps the JSON in Java serialisation framing before it stores it.
                // With it, the stored bytes are the JSON text itself (UTF-8).
                .plainTextStateSerializerLegacyMode(true)
                .createTables(false)
                .build();
        log.info("event=checkpoint_saver_created host={} port={} database={} stateFormat=json createTables=false",
                host, port, database);
        return saver;
    }
}
