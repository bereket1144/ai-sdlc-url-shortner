package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;

class RunMigrationTest {

    @Test
    @DisplayName("AC-RUN-6.6, 6.7, AC-FLOW-1.6 an older run gets CREATED state and GREENFIELD kind")
    void runStoredBeforeTheChangeIsFilledInByTheMigration() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE))) {
            postgres.start();
            UUID id = UUID.randomUUID();

            migrateTo(postgres, "1");
            try (Connection connection = connect(postgres); Statement statement = connection.createStatement()) {
                statement.executeUpdate("insert into runs (id, requirement) values ('" + id + "', 'old run')");
            }
            migrateTo(postgres, "latest");

            try (Connection connection = connect(postgres);
                    Statement statement = connection.createStatement();
                    ResultSet row = statement.executeQuery(
                            "select requirement, kind, status, created_at, updated_at, version from runs where id = '" + id + "'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("requirement")).isEqualTo("old run");
                assertThat(row.getString("kind")).isEqualTo("GREENFIELD");
                assertThat(row.getString("status")).isEqualTo("CREATED");
                assertThat(row.getTimestamp("created_at")).isNotNull();
                assertThat(row.getTimestamp("updated_at")).isNotNull();
                assertThat(row.getLong("version")).isZero();
            }

            // The migration's defaults were dropped again: a new row must supply its own state.
            try (Connection connection = connect(postgres); Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "insert into runs (id, requirement) values ('" + UUID.randomUUID() + "', 'no state')"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("null value in column");
            }
        }
    }

    @Test
    @DisplayName("AC-AGT-9.4 a run failed before failure codes existed reads back as UNEXPECTED")
    void olderFailedRunGetsUnexpectedReason() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE))) {
            postgres.start();
            UUID id = UUID.randomUUID();
            migrateTo(postgres, "1");
            try (Connection connection = connect(postgres); Statement statement = connection.createStatement()) {
                statement.executeUpdate("insert into runs (id, requirement) values ('" + id + "', 'old run')");
            }
            migrateTo(postgres, "6");
            try (Connection connection = connect(postgres); Statement statement = connection.createStatement()) {
                statement.executeUpdate("update runs set status = 'FAILED' where id = '" + id + "'");
            }
            migrateTo(postgres, "latest");

            try (Connection connection = connect(postgres); Statement statement = connection.createStatement();
                    ResultSet row = statement.executeQuery(
                            "select status, failure_reason from runs where id = '" + id + "'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("status")).isEqualTo("FAILED");
                assertThat(row.getString("failure_reason")).isEqualTo("UNEXPECTED");
            }
            try (Connection connection = connect(postgres); Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "update runs set failure_reason = null where id = '" + id + "'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("runs_failure_reason_matches_status");
                assertThatThrownBy(() -> statement.executeUpdate(
                        "update runs set failure_reason = 'MADE_UP' where id = '" + id + "'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("runs_failure_reason_known");
            }
        }
    }

    private static void migrateTo(PostgreSQLContainer postgres, String target) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(target)
                .load()
                .migrate();
    }

    private static Connection connect(PostgreSQLContainer postgres) throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
