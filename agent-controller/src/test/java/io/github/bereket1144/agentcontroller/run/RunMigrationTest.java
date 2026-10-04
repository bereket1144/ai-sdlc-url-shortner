package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class RunMigrationTest {

    @Test
    @DisplayName("AC-RUN-6.6 a run stored before the state columns existed gets status CREATED, both times and version 0")
    void runStoredBeforeTheChangeIsFilledInByTheMigration() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:latest"))) {
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
                            "select requirement, status, created_at, updated_at, version from runs where id = '" + id + "'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("requirement")).isEqualTo("old run");
                assertThat(row.getString("status")).isEqualTo("CREATED");
                assertThat(row.getTimestamp("created_at")).isNotNull();
                assertThat(row.getTimestamp("updated_at")).isNotNull();
                assertThat(row.getLong("version")).isZero();
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
