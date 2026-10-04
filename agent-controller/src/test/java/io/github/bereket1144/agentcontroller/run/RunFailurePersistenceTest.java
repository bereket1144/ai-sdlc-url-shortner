package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, RunService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunFailurePersistenceTest {

    @Autowired
    private RunService runService;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID createdId;

    @AfterEach
    void deleteCreatedRun() {
        if (createdId != null) {
            runRepository.deleteById(createdId);
        }
    }

    @Test
    @DisplayName("AC-EXEC-5.1 a failed run status survives caller rollback and can be read back")
    void failsAndPersistsClaimedRun() {
        createdId = runService.createRun("Build a task tracker").getId();
        Run claimed = runService.claimNextRun().orElseThrow();
        assertThat(claimed.getId()).isEqualTo(createdId);

        IllegalStateException workflowFailure = new IllegalStateException("Workflow failed");
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);
        // Simulate a caller rethrowing the workflow error after failure recording.
        assertThatThrownBy(() -> callerTransaction.execute(status -> {
            runService.failRun(createdId);
            throw workflowFailure;
        })).isSameAs(workflowFailure);

        assertThat(runService.getRun(createdId).getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    @DisplayName("EXEC-5 recording failure for a missing run fails explicitly")
    void rejectsMissingRun() {
        assertThatThrownBy(() -> runService.failRun(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run not found while recording failure");
    }

    @Test
    @DisplayName("AC-EXEC-5.3 recording failure for a stored CREATED run is rejected and the row is unchanged")
    void rejectsFailingCreatedRun() {
        createdId = runService.createRun("Build a task tracker").getId();

        assertThatThrownBy(() -> runService.failRun(createdId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a RUNNING run can fail");

        assertThat(runService.getRun(createdId).getStatus()).isEqualTo(RunStatus.CREATED);
    }

    @Test
    @DisplayName("AC-EXEC-5.3 recording failure for a stored SUCCEEDED run is rejected and the row is unchanged")
    void rejectsFailingSucceededRun() {
        createdId = runService.createRun("Build a task tracker").getId();
        runService.claimNextRun().orElseThrow();
        runService.succeedRun(createdId);

        assertThatThrownBy(() -> runService.failRun(createdId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a RUNNING run can fail");

        assertThat(runService.getRun(createdId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }
}
