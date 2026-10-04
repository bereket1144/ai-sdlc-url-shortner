package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;

class WorkflowRunnerParallelTest {

    private enum Branch { QA, REVIEW, DOCUMENTATION }

    @ParameterizedTest(name = "{0} finishes last")
    @EnumSource(Branch.class)
    @DisplayName("AC-FLOW-1.4 QA, review, and documentation overlap and all finish before collect, whichever finishes last")
    void parallelStagesJoinBeforeTheGraphEnds(Branch heldBranch) throws Exception {
        DummySdlcNodes nodes = spy(new DummySdlcNodes());
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch otherBranchesFinished = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch collectEntered = new CountDownLatch(1);

        // Each branch waits until all three have started, so a sequential execution cannot pass.
        Answer<Object> held = invocation -> {
            allStarted.countDown();
            await(allStarted, "parallel stages did not overlap");
            await(release, "the held branch was not released");
            return invocation.callRealMethod();
        };
        Answer<Object> free = invocation -> {
            allStarted.countDown();
            await(allStarted, "parallel stages did not overlap");
            Object output = invocation.callRealMethod();
            otherBranchesFinished.countDown();
            return output;
        };
        doAnswer(heldBranch == Branch.QA ? held : free).when(nodes).qa(any());
        doAnswer(heldBranch == Branch.REVIEW ? held : free).when(nodes).review(any());
        doAnswer(heldBranch == Branch.DOCUMENTATION ? held : free).when(nodes).documentation(any());
        doAnswer(invocation -> {
            collectEntered.countDown();
            return invocation.callRealMethod();
        }).when(nodes).collect(any());

        WorkflowRunner runner = new WorkflowRunner(nodes);
        ExecutorService graphThread = Executors.newSingleThreadExecutor();
        try {
            Future<AgentState> run = graphThread.submit(() ->
                    runner.run(UUID.randomUUID(), "Build a task tracker", RunKind.GREENFIELD));

            await(allStarted, "parallel stages did not overlap");
            await(otherBranchesFinished, "the two free branches did not finish");
            assertThat(collectEntered.await(300, TimeUnit.MILLISECONDS))
                    .as("the join ran while %s was still held", heldBranch)
                    .isFalse();

            release.countDown();
            AgentState result = run.get(5, TimeUnit.SECONDS);
            assertThat(collectEntered.getCount()).isZero();
            assertThat(result.<String>value("qa")).contains("qa:done");
            assertThat(result.<String>value("review")).contains("review:done");
            assertThat(result.<String>value("documentation")).contains("documentation:done");
            assertThat(result.<Boolean>value("joined")).contains(true);
        } finally {
            release.countDown();
            graphThread.shutdownNow();
        }
    }

    @Test
    @DisplayName("AC-EXEC-5.1, AC-FLOW-1.4 a failing branch is reported at once and its held sibling is interrupted, not waited for")
    void failingBranchDoesNotWaitForAHeldSibling() throws Exception {
        DummySdlcNodes nodes = spy(new DummySdlcNodes());
        CountDownLatch reviewStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        CountDownLatch reviewInterrupted = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException("QA broke");

        doAnswer(invocation -> {
            await(reviewStarted, "review did not start");
            throw failure;
        }).when(nodes).qa(any());
        doAnswer(invocation -> {
            reviewStarted.countDown();
            try {
                neverReleased.await(); // only the test's finally block or an interrupt ends this wait
            } catch (InterruptedException interrupted) {
                reviewInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return invocation.callRealMethod();
        }).when(nodes).review(any());

        WorkflowRunner runner = new WorkflowRunner(nodes);
        ExecutorService graphThread = Executors.newSingleThreadExecutor();
        try {
            Future<AgentState> run = graphThread.submit(() ->
                    runner.run(UUID.randomUUID(), "Build a task tracker", RunKind.GREENFIELD));

            // Without shutdownNow() the executor's close() would wait for the held review and this get() would time out.
            assertThatThrownBy(() -> run.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .rootCause().isSameAs(failure);
            await(reviewInterrupted, "the held sibling was not interrupted");
        } finally {
            neverReleased.countDown();
            graphThread.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError(message);
        }
    }
}
