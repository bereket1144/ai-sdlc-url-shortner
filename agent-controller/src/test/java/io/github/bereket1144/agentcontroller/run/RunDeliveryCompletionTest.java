package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import io.github.bereket1144.agentcontroller.workspace.DeliveryBlockedException;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceMerge;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "workspace.auto-merge=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, RunService.class, RunDispatcher.class, RunDelivery.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RunDeliveryCompletionTest {

    @Autowired RunDispatcher dispatcher;
    @Autowired RunService service;
    @Autowired RunRepository runs;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean WorkflowRunner workflow;
    @MockitoBean VerificationRecords verifications;
    @MockitoBean WorkspaceMerge merge;

    private UUID runId;

    @BeforeEach
    void createRun() {
        runId = service.createRun("Build a task tracker").getId();
        when(workflow.run(runId, "Build a task tracker", RunKind.GREENFIELD, null))
                .thenReturn(new AgentState(Map.of()));
        when(verifications.latest(runId)).thenReturn(Optional.of(
                new VerificationResult(Outcome.PASSED, 1, 1, 0, List.of(), "", "tested-commit")));
    }

    @AfterEach
    void cleanUp() {
        runs.deleteById(runId);
    }

    @Test
    @DisplayName("AC-AGT-68.1, 68.5 completion delivers only after the workflow returns and holds the run lock "
            + "through delivery before recording SUCCEEDED")
    void deliversWithinTheFinalTransition() {
        doAnswer(invocation -> {
            TransactionTemplate separate = new TransactionTemplate(transactions);
            separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            // NOWAIT proves another connection cannot claim the row for Stop while delivery owns it.
            assertThatThrownBy(() -> separate.execute(status -> jdbc.queryForObject(
                    "select id from runs where id = ? for update nowait", UUID.class, runId)))
                    .isInstanceOf(DataAccessException.class);
            return "merge-commit";
        }).when(merge).merge(runId);

        assertThat(dispatcher.dispatchNext()).isTrue();

        var order = inOrder(workflow, merge);
        order.verify(workflow).run(runId, "Build a task tracker", RunKind.GREENFIELD, null);
        order.verify(merge).merge(runId);
        assertThat(service.getRun(runId).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("AC-AGT-68.3 a blocked delivery records FAILED and the stable DELIVERY_BLOCKED reason")
    void recordsADeliveryFailure() {
        DeliveryBlockedException failure = new DeliveryBlockedException("DIRTY_CHECKOUT");
        doThrow(failure).when(merge).merge(runId);

        assertThatThrownBy(dispatcher::dispatchNext).isSameAs(failure);

        Run run = service.getRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo(RunFailureReason.DELIVERY_BLOCKED);
    }

    @Test
    @DisplayName("AC-AGT-68.5 a stop accepted just before completion prevents delivery")
    void respectsAnAcceptedStopBeforeDelivery() {
        doAnswer(invocation -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> runs.requestStop(runId));
            return new AgentState(Map.of());
        }).when(workflow).run(runId, "Build a task tracker", RunKind.GREENFIELD, null);

        assertThat(dispatcher.dispatchNext()).isTrue();

        verifyNoInteractions(merge);
        assertThat(service.getRun(runId).getStatus()).isEqualTo(RunStatus.STOPPED);
    }

    @Test
    @DisplayName("AC-AGT-68.1 work without a passing verification is not merged")
    void requiresAPassingVerification() {
        when(verifications.latest(runId)).thenReturn(Optional.of(VerificationResult.noResult("untested")));

        assertThatThrownBy(dispatcher::dispatchNext).isInstanceOf(DeliveryBlockedException.class)
                .hasMessage("VERIFICATION_NOT_PASSED");

        verifyNoInteractions(merge);
        assertThat(service.getRun(runId).getFailureReason()).isEqualTo(RunFailureReason.DELIVERY_BLOCKED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "STOPPED", "WAITING_FOR_INPUT"})
    @DisplayName("AC-AGT-68.5 failed, stopped and waiting workflows do not deliver work")
    void doesNotDeliverAnUnfinishedRun(String outcome) {
        if (outcome.equals("FAILED")) {
            doThrow(new IllegalStateException("Stage failed")).when(workflow)
                    .run(runId, "Build a task tracker", RunKind.GREENFIELD, null);
            assertThatThrownBy(dispatcher::dispatchNext).isInstanceOf(IllegalStateException.class);
        } else if (outcome.equals("STOPPED")) {
            doThrow(new RunStoppedException()).when(workflow)
                    .run(runId, "Build a task tracker", RunKind.GREENFIELD, null);
            assertThat(dispatcher.dispatchNext()).isTrue();
            assertThat(service.getRun(runId).getStatus()).isEqualTo(RunStatus.STOPPED);
        } else {
            when(workflow.run(runId, "Build a task tracker", RunKind.GREENFIELD, null))
                    .thenReturn(new AgentState(Map.of("waitingForInput", true)));
            assertThat(dispatcher.dispatchNext()).isTrue();
        }

        verifyNoInteractions(merge);
    }
}
