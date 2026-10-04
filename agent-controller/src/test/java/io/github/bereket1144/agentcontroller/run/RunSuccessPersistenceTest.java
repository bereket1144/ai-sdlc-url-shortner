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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, RunService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunSuccessPersistenceTest {

    @Autowired
    private RunService runService;

    @Autowired
    private RunRepository runRepository;

    private UUID createdId;

    @AfterEach
    void deleteCreatedRun() {
        if (createdId != null) {
            runRepository.deleteById(createdId);
        }
    }

    @Test
    @DisplayName("AC-EXEC-3.1 a successful run status is committed and can be read back")
    void succeedsAndPersistsClaimedRun() {
        createdId = runService.createRun("Build a task tracker").getId();
        Run claimed = runService.claimNextRun().orElseThrow();
        assertThat(claimed.getId()).isEqualTo(createdId);

        runService.succeedRun(createdId);

        assertThat(runService.getRun(createdId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("EXEC-3 recording success for a missing run fails explicitly")
    void rejectsMissingRun() {
        assertThatThrownBy(() -> runService.succeedRun(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Run not found while recording success");
    }
}
