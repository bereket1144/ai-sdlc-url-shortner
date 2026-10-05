package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bereket1144.agentcontroller.agent.InputAccessDeniedException;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.run.RunFailureReason;
import io.github.bereket1144.agentcontroller.run.RunKind;
import io.github.bereket1144.agentcontroller.sandbox.VerificationFailedException;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The stage wrapper of the workflow runner on the dummy nodes: a QA node that fails stands for any stage. */
class StageRetryTest {

    private static final String REQUIREMENT = "Build a task tracker";

    private final DummySdlcNodes nodes = spy(new DummySdlcNodes());
    private final StageProgress progress = mock(StageProgress.class);
    private final UUID runId = UUID.randomUUID();

    @Test
    @DisplayName("AC-AGT-65.1, 65.5, 65.8 a stage that fails for a retryable reason is attempted again and is RUNNING "
            + "again, and each failed attempt is logged with the run, stage, attempt and reason code only")
    void aRetryableFailureIsAttemptedAgain() throws Exception {
        doThrow(new VerificationFailedException(Outcome.FAILED), new IllegalStateException("PRIVATE_DETAIL"))
                .doCallRealMethod().when(nodes).qa(any());

        List<String> messages = captureLogs(() -> {
            AgentState result = runner(2).run(runId, REQUIREMENT, RunKind.GREENFIELD);
            assertThat(result.<Boolean>value("joined")).contains(true);
        });

        verify(nodes, times(3)).qa(any());
        verify(progress, times(3)).started(runId, "qa");
        verify(progress).finished(runId, "qa");
        verify(progress, never()).failed(any(), any());
        assertThat(messages).filteredOn(message -> message.startsWith("event=stage_retry")).containsExactly(
                "event=stage_retry runId=" + runId + " stage=qa attempt=1 reason=VERIFICATION_FAILED",
                "event=stage_retry runId=" + runId + " stage=qa attempt=2 reason=UNEXPECTED");
        assertThat(messages).noneMatch(message -> message.contains("PRIVATE_DETAIL"));
    }

    @Test
    @DisplayName("AC-AGT-65.1, 65.5, 65.8 a failure with a reason that is not retryable is not attempted again, the "
            + "stage is FAILED and the attempt is logged with its reason code")
    void aFailureThatIsNotRetryableEndsTheRunAtOnce() throws Exception {
        InputAccessDeniedException failure = new InputAccessDeniedException("qa", InputKind.ORIGINAL_REQUIREMENT);
        doThrow(failure).when(nodes).qa(any());
        WorkflowRunner runner = runner(2);

        List<String> messages = captureLogs(() ->
                assertThat(catchThrowable(() -> runner.run(runId, REQUIREMENT, RunKind.GREENFIELD)))
                        .hasRootCauseInstanceOf(InputAccessDeniedException.class));

        verify(nodes, times(1)).qa(any());
        verify(progress, times(1)).started(runId, "qa");
        verify(progress).failed(runId, "qa");
        assertThat(messages).filteredOn(message -> message.startsWith("event=stage_"))
                .containsExactly("event=stage_failed runId=" + runId + " stage=qa attempt=1 reason=ACCESS_DENIED");
    }

    @Test
    @DisplayName("AC-AGT-65.4, 65.5 when every attempt fails the stage is FAILED and the run ends with the reason of "
            + "the last failure")
    void everyAttemptFailing() throws Exception {
        doThrow(new IllegalStateException("first"), new IllegalStateException("second"),
                new VerificationFailedException(Outcome.FAILED)).when(nodes).qa(any());
        WorkflowRunner runner = runner(2);

        Throwable thrown = catchThrowable(() -> runner.run(runId, REQUIREMENT, RunKind.GREENFIELD));

        assertThat(RunFailureReason.from(thrown)).isEqualTo(RunFailureReason.VERIFICATION_FAILED);
        verify(nodes, times(3)).qa(any());
        verify(progress, times(3)).started(runId, "qa");
        verify(progress).failed(runId, "qa");
        verify(progress, never()).finished(runId, "qa");
    }

    private WorkflowRunner runner(int maxRetries) throws GraphStateException {
        return new WorkflowRunner(nodes, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(progress),
                Optional.empty(), maxRetries);
    }

    /** The lines of the runner's logger written while the action runs; a branch thread may write some of them. */
    private static List<String> captureLogs(ThrowingRunnable action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(WorkflowRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.list = Collections.synchronizedList(new ArrayList<>());
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
