package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkflowRunnerParallelTest {

    @Test
    @DisplayName("AC-FLOW-1.4 QA, review, and documentation overlap and all finish before collect")
    void parallelStagesJoinBeforeTheGraphEnds() throws Exception {
        DummySdlcNodes nodes = spy(new DummySdlcNodes());
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch otherBranchesFinished = new CountDownLatch(2);
        CountDownLatch releaseQa = new CountDownLatch(1);
        CountDownLatch collectEntered = new CountDownLatch(1);

        // Each branch waits until all three have started, so a sequential execution cannot pass.
        doAnswer(invocation -> {
            allStarted.countDown();
            await(allStarted, "parallel stages did not overlap");
            await(releaseQa, "QA was not released");
            return invocation.callRealMethod();
        }).when(nodes).qa(any());
        doAnswer(invocation -> {
            allStarted.countDown();
            await(allStarted, "parallel stages did not overlap");
            Object output = invocation.callRealMethod();
            otherBranchesFinished.countDown();
            return output;
        }).when(nodes).review(any());
        doAnswer(invocation -> {
            allStarted.countDown();
            await(allStarted, "parallel stages did not overlap");
            Object output = invocation.callRealMethod();
            otherBranchesFinished.countDown();
            return output;
        }).when(nodes).documentation(any());
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
            await(otherBranchesFinished, "review and documentation did not finish");
            assertThat(collectEntered.await(300, TimeUnit.MILLISECONDS))
                    .as("the join ran while QA was still held")
                    .isFalse();

            releaseQa.countDown();
            AgentState result = run.get(5, TimeUnit.SECONDS);
            assertThat(collectEntered.getCount()).isZero();
            assertThat(result.<String>value("qa")).contains("qa:done");
            assertThat(result.<String>value("review")).contains("review:done");
            assertThat(result.<String>value("documentation")).contains("documentation:done");
            assertThat(result.<Boolean>value("joined")).contains(true);
        } finally {
            releaseQa.countDown();
            graphThread.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError(message);
        }
    }
}
