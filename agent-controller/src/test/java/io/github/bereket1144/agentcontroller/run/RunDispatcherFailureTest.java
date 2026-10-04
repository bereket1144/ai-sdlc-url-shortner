package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunDispatcherFailureTest {

    @Test
    @DisplayName("EXEC-5 the workflow error remains primary if recording FAILED also fails")
    void preservesWorkflowFailureWhenFailureRecordingFails() {
        RunService runService = mock(RunService.class);
        WorkflowRunner workflowRunner = mock(WorkflowRunner.class);
        Run run = mock(Run.class);
        UUID runId = UUID.randomUUID();
        String requirement = "Build a task tracker";
        IllegalStateException workflowFailure = new IllegalStateException("Workflow failed");
        IllegalStateException recordingFailure = new IllegalStateException("Could not record failure");
        when(run.getId()).thenReturn(runId);
        when(run.getRequirement()).thenReturn(requirement);
        when(runService.claimNextRun()).thenReturn(Optional.of(run));
        doThrow(workflowFailure).when(workflowRunner).run(runId, requirement);
        doThrow(recordingFailure).when(runService).failRun(runId);

        assertThatThrownBy(() -> new RunDispatcher(runService, workflowRunner).dispatchNext())
                .isSameAs(workflowFailure);
        assertThat(workflowFailure.getSuppressed()).containsExactly(recordingFailure);
    }

    @Test
    @DisplayName("EXEC-3 a failure to record success does not mark a completed workflow FAILED")
    void doesNotRecordWorkflowFailureWhenSuccessRecordingFails() {
        RunService runService = mock(RunService.class);
        WorkflowRunner workflowRunner = mock(WorkflowRunner.class);
        Run run = mock(Run.class);
        UUID runId = UUID.randomUUID();
        String requirement = "Build a task tracker";
        IllegalStateException recordingFailure = new IllegalStateException("Could not record success");
        when(run.getId()).thenReturn(runId);
        when(run.getRequirement()).thenReturn(requirement);
        when(runService.claimNextRun()).thenReturn(Optional.of(run));
        doThrow(recordingFailure).when(runService).succeedRun(runId);

        assertThatThrownBy(() -> new RunDispatcher(runService, workflowRunner).dispatchNext())
                .isSameAs(recordingFailure);
        verify(runService, never()).failRun(runId);
    }
}
