package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.workflow.DummySdlcNodes;
import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
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
@Import({TestcontainersConfiguration.class, RunService.class, RunDispatcher.class,
        WorkflowRunner.class, DummySdlcNodes.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunSdlcFlowTest {

    @Autowired
    private RunService runService;

    @Autowired
    private RunDispatcher runDispatcher;

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
    @DisplayName("AC-FLOW-1.5 live dispatch runs the dummy graph and persists SUCCEEDED")
    void dispatchedDummyGraphCompletesRun() {
        createdId = runService.createRun("Build a task tracker").getId();

        assertThat(runDispatcher.dispatchNext()).isTrue();

        Run saved = runService.getRun(createdId);
        assertThat(saved.getKind()).isEqualTo(RunKind.GREENFIELD);
        assertThat(saved.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("AC-FLOW-1.5, 1.6 live dispatch of a brownfield run runs the dummy graph and persists SUCCEEDED")
    void dispatchedBrownfieldGraphCompletesRun() {
        createdId = runService.createRun("Add a task deadline", RunKind.BROWNFIELD).getId();

        assertThat(runDispatcher.dispatchNext()).isTrue();

        Run saved = runService.getRun(createdId);
        assertThat(saved.getKind()).isEqualTo(RunKind.BROWNFIELD);
        assertThat(saved.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }
}
