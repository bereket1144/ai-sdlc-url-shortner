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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RunDispatcherFailureTest {

    @Test
    @DisplayName("AC-EXEC-5.1, 5.2 a workflow that throws an Error is stored as FAILED and the same Error reaches the caller")
    void recordsFailureAndRethrowsWorkflowError() {
        RunService runService = mock(RunService.class);
        WorkflowRunner workflowRunner = mock(WorkflowRunner.class);
        Run run = mock(Run.class);
        UUID runId = UUID.randomUUID();
        String requirement = "Build a task tracker";
        AssertionError workflowError = new AssertionError("Workflow broke");
        when(run.getId()).thenReturn(runId);
        when(run.getRequirement()).thenReturn(requirement);
        when(runService.claimNextRun()).thenReturn(Optional.of(run));
        doThrow(workflowError).when(workflowRunner).run(runId, requirement);

        assertThatThrownBy(() -> new RunDispatcher(runService, workflowRunner).dispatchNext())
                .isSameAs(workflowError);
        verify(runService).failRun(runId);
    }

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
    @DisplayName("EXEC-3 a failure to record success is logged with the run id, rethrown, and does not mark a completed workflow FAILED")
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

        Logger dispatcherLog = (Logger) LoggerFactory.getLogger(RunDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        dispatcherLog.addAppender(appender);
        try {
            assertThatThrownBy(() -> new RunDispatcher(runService, workflowRunner).dispatchNext())
                    .isSameAs(recordingFailure);
        } finally {
            dispatcherLog.detachAppender(appender);
        }

        verify(runService, never()).failRun(runId);
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("event=run_success_recording_failed runId=" + runId
                        + " errorType=java.lang.IllegalStateException")
                .noneMatch(message -> message.startsWith("event=run_dispatch_failed"));
    }
}
