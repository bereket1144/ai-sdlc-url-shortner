package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
        when(run.getKind()).thenReturn(RunKind.GREENFIELD);
        when(runService.claimNextRun()).thenReturn(Optional.of(run));
        doThrow(workflowError).when(workflowRunner).run(runId, requirement, RunKind.GREENFIELD);

        assertThatThrownBy(() -> new RunDispatcher(runService, workflowRunner).dispatchNext())
                .isSameAs(workflowError);
        verify(runService).failRun(runId, workflowError);
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
        when(run.getKind()).thenReturn(RunKind.GREENFIELD);
        when(runService.claimNextRun()).thenReturn(Optional.of(run));
        doThrow(workflowFailure).when(workflowRunner).run(runId, requirement, RunKind.GREENFIELD);
        doThrow(recordingFailure).when(runService).failRun(runId, workflowFailure);

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
        when(run.getKind()).thenReturn(RunKind.GREENFIELD);
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

        verify(runService, never()).failRun(eq(runId), any(Throwable.class));
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .contains("event=run_success_recording_failed runId=" + runId
                        + " errorType=java.lang.IllegalStateException")
                .noneMatch(message -> message.startsWith("event=run_dispatch_failed"));
    }

    @Test
    @DisplayName("EXEC-5 a failure that the graph library wrapped is logged by the class of its root cause")
    void logsRootCauseOfWrappedWorkflowFailure() {
        RuntimeException wrapped = new CompletionException(new RuntimeException(new IllegalStateException("QA broke")));

        assertThat(dispatchFailureLogFor(wrapped))
                .endsWith("errorType=java.lang.IllegalStateException");
    }

    @Test
    @DisplayName("EXEC-5 a failure with no cause is logged by its own class")
    void logsOwnClassOfUnwrappedWorkflowFailure() {
        assertThat(dispatchFailureLogFor(new IllegalArgumentException("Workflow failed")))
                .endsWith("errorType=java.lang.IllegalArgumentException");
    }

    @Test
    @DisplayName("EXEC-5 a loop in the cause chain does not stop the failure from being logged and rethrown")
    @Timeout(5)
    void logsFailureWhoseCauseChainLoops() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new IllegalStateException("second", first);
        first.initCause(second);

        // The walk stops after 20 steps, so the two-step loop ends on the first exception.
        assertThat(dispatchFailureLogFor(first))
                .contains("event=run_dispatch_failed")
                .endsWith("errorType=java.lang.RuntimeException");
    }

    // Dispatches one run whose workflow throws the given failure; returns the run_dispatch_failed log line.
    private static String dispatchFailureLogFor(RuntimeException workflowFailure) {
        RunService runService = mock(RunService.class);
        WorkflowRunner workflowRunner = mock(WorkflowRunner.class);
        Run run = mock(Run.class);
        UUID runId = UUID.randomUUID();
        String requirement = "Build a task tracker";
        when(run.getId()).thenReturn(runId);
        when(run.getRequirement()).thenReturn(requirement);
        when(run.getKind()).thenReturn(RunKind.GREENFIELD);
        when(runService.claimNextRun()).thenReturn(Optional.of(run));
        doThrow(workflowFailure).when(workflowRunner).run(runId, requirement, RunKind.GREENFIELD);

        Logger dispatcherLog = (Logger) LoggerFactory.getLogger(RunDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        dispatcherLog.addAppender(appender);
        try {
            assertThatThrownBy(() -> new RunDispatcher(runService, workflowRunner).dispatchNext())
                    .isSameAs(workflowFailure);
        } finally {
            dispatcherLog.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("event=run_dispatch_failed runId=" + runId))
                .findFirst().orElseThrow();
    }
}
