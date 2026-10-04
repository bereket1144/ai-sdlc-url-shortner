package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, RunService.class, RunDispatcher.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunDispatcherCompletionTest {

    @Autowired
    private RunDispatcher runDispatcher;

    @Autowired
    private RunService runService;

    @Autowired
    private RunRepository runRepository;

    @MockitoBean
    private WorkflowRunner workflowRunner;

    private UUID createdId;

    @AfterEach
    void deleteCreatedRun() {
        if (createdId != null) {
            runRepository.deleteById(createdId);
        }
    }

    @Test
    @DisplayName("AC-EXEC-3.1 a completed workflow leaves the run stored as SUCCEEDED")
    void recordsSuccessAfterWorkflowReturns() {
        String requirement = "Build a task tracker";
        createdId = runService.createRun(requirement).getId();
        when(workflowRunner.run(createdId, requirement, RunKind.GREENFIELD))
                .thenReturn(new AgentState(Map.<String, Object>of("receipt", "completed")));

        assertThat(runDispatcher.dispatchNext()).isTrue();

        verify(workflowRunner).run(createdId, requirement, RunKind.GREENFIELD);
        assertThat(runService.getRun(createdId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("AC-EXEC-3.2, 5.1, 5.2 a failed workflow is stored as FAILED and reaches the caller")
    void recordsFailureAndRethrowsWorkflowException() {
        String requirement = "Build a task tracker";
        createdId = runService.createRun(requirement).getId();
        IllegalStateException failure = new IllegalStateException("Workflow failed");
        doThrow(failure).when(workflowRunner).run(createdId, requirement, RunKind.GREENFIELD);

        assertThatThrownBy(runDispatcher::dispatchNext).isSameAs(failure);

        assertThat(runService.getRun(createdId).getStatus()).isEqualTo(RunStatus.FAILED);
    }
}
