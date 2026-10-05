package io.github.bereket1144.agentcontroller.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;

/**
 * Starts from an empty PostgreSQL, so it can see who creates the checkpoint tables. The proofs in
 * {@code ApprovalPauseCheckpointTest} and {@code ProcessRestartTest} then show that the saver works on them.
 */
@Testcontainers
class CheckpointTablesTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE));

    @Test
    @DisplayName("AC-AGT-34.1 the migration creates the checkpoint tables and building the saver does not")
    void theMigrationCreatesTheCheckpointTablesAndTheSaverDoesNot() throws Exception {
        CheckpointSaverFactory.create(POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(), POSTGRES.getPassword());
        assertThat(checkpointTables()).as("tables after building a saver on an empty database").isEmpty();

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        assertThat(checkpointTables()).as("tables after the migrations")
                .containsExactlyInAnyOrder("lg4jthread", "lg4jcheckpoint");
    }

    private static List<String> checkpointTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'lg4j%'")) {
            List<String> names = new ArrayList<>();
            while (rows.next()) {
                names.add(rows.getString("table_name"));
            }
            return names;
        }
    }
}
