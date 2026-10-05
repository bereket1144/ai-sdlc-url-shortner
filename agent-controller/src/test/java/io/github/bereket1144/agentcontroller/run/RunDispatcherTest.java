package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.bsc.langgraph4j.state.AgentState;
import java.util.Map;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, RunService.class, RunDispatcher.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunDispatcherTest {

    @Autowired
    private RunDispatcher runDispatcher;

    @Autowired
    private RunService runService;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private WorkflowRunner workflowRunner;

    private UUID createdId;

    @BeforeEach
    void completedWorkflowByDefault() {
        when(workflowRunner.run(any(UUID.class), anyString(), any(RunKind.class), isNull()))
                .thenReturn(new AgentState(Map.of()));
    }

    @AfterEach
    void deleteCreatedRun() {
        if (createdId != null) {
            runRepository.deleteById(createdId);
        }
    }

    @Test
    @DisplayName("AC-EXEC-2.1, 2.2 dispatch uses saved input after RUNNING is committed")
    void dispatchesClaimedRunAfterStatusCommit() {
        String requirement = "Build a task tracker";
        createdId = runService.createRun(requirement).getId();

        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);
        TransactionTemplate separateRead = new TransactionTemplate(transactionManager);
        separateRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Read in another transaction so an uncommitted RUNNING change cannot satisfy this check.
        doAnswer(invocation -> {
            UUID runId = invocation.getArgument(0);
            String storedStatus = separateRead.execute(status -> jdbcTemplate.queryForObject(
                    "select status from runs where id = ?", String.class, runId));
            assertThat(storedStatus).isEqualTo("RUNNING");
            return new AgentState(Map.of());
        }).when(workflowRunner).run(any(UUID.class), anyString(), eq(RunKind.GREENFIELD), isNull());

        Boolean dispatched = callerTransaction.execute(status -> runDispatcher.dispatchNext());
        assertThat(dispatched).isTrue();
        verify(workflowRunner).run(createdId, requirement, RunKind.GREENFIELD, null);
    }

    @Test
    @DisplayName("AC-EXEC-2.3 an empty queue does not start a workflow")
    void doesNotStartWorkflowWhenNoRunIsPending() {
        assertThat(runRepository.count()).isZero();

        assertThat(runDispatcher.dispatchNext()).isFalse();

        verifyNoInteractions(workflowRunner);
    }

    @Test
    @DisplayName("EXEC-2 a workflow failure reaches the caller")
    void propagatesWorkflowFailure() {
        createdId = runService.createRun("Build a task tracker").getId();
        IllegalStateException failure = new IllegalStateException("Workflow failed");
        doThrow(failure).when(workflowRunner).run(any(UUID.class), anyString(), eq(RunKind.GREENFIELD), isNull());

        assertThatThrownBy(runDispatcher::dispatchNext).isSameAs(failure);
    }

    @Test
    @DisplayName("AC-FLOW-1.6 dispatch passes a saved brownfield kind to the graph")
    void dispatchesSavedBrownfieldKind() {
        String requirement = "Add a task deadline";
        createdId = runService.createRun(requirement, RunKind.BROWNFIELD).getId();

        assertThat(runDispatcher.dispatchNext()).isTrue();

        verify(workflowRunner).run(createdId, requirement, RunKind.BROWNFIELD, null);
        assertThat(runService.getRun(createdId).getKind()).isEqualTo(RunKind.BROWNFIELD);
    }
}
