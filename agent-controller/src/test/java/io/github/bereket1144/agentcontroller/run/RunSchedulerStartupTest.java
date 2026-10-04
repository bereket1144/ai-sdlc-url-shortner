package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The run is inserted before the Spring context exists. JUnit starts the shared container and runs
 * {@link #saveCreatedRunBeforeApplicationStarts()} first; only then does Spring start the application
 * against that same database with polling on. No timing is involved in "saved before startup".
 */
@Testcontainers
// Stop the 25 ms poll when this class ends.
@DirtiesContext
@SpringBootTest(properties = {
        "run.dispatch.polling-enabled=true",
        "run.dispatch.poll-delay-ms=25"
})
class RunSchedulerStartupTest {

    private static final String REQUIREMENT = "Build a task tracker";
    private static final UUID SAVED_ID = UUID.randomUUID();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE));

    @Autowired
    private RunService runService;

    @Autowired
    private RunRepository runRepository;

    @MockitoBean
    private WorkflowRunner workflowRunner;

    @BeforeAll
    static void saveCreatedRunBeforeApplicationStarts() throws SQLException {
        // The schema normally appears when the application starts, so create it here with the same migrations.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO runs (id, requirement, status, created_at, updated_at, version)"
                                + " VALUES (?, ?, 'CREATED', now(), now(), 0)")) {
            insert.setObject(1, SAVED_ID);
            insert.setString(2, REQUIREMENT);
            insert.executeUpdate();
        }
    }

    @AfterEach
    void deleteSavedRun() {
        runRepository.deleteById(SAVED_ID);
    }

    @Test
    @DisplayName("AC-EXEC-4.1 polling starts a run that was saved before the application started")
    void scheduledPollingStartsRunSavedBeforeStartup() throws InterruptedException {
        awaitStatus(SAVED_ID, RunStatus.SUCCEEDED);

        verify(workflowRunner, times(1)).run(SAVED_ID, REQUIREMENT);
    }

    private void awaitStatus(UUID runId, RunStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (runService.getRun(runId).getStatus() == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(runService.getRun(runId).getStatus()).isEqualTo(expected);
    }
}
