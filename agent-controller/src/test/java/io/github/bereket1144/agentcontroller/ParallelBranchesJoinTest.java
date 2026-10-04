package io.github.bereket1144.agentcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.Test;

/**
 * Runs the real LangGraph4j engine with two fake tasks (no model calls) and checks the
 * properties the controller will rely on: parallel branches overlap, the stage after them waits
 * until both have finished, and a branch that crashes fails the run instead of counting as a pass.
 *
 * <p>LangGraph4j only runs fan-out branches on separate threads when the run supplies an executor
 * for the node that fans out ({@code addParallelNodeExecutor}). Without one the branches run one
 * after the other, so the test passes an executor and a missing one would make it fail.
 */
class ParallelBranchesJoinTest {

    private static final Duration PATIENCE = Duration.ofSeconds(5);
    private static final Duration GRACE = Duration.ofMillis(300);

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch bothStarted = new CountDownLatch(2);
    private final CountDownLatch fastTaskFinished = new CountDownLatch(1);
    private final CountDownLatch slowTaskMayFinish = new CountDownLatch(1);
    private final CountDownLatch joinStarted = new CountDownLatch(1);

    @Test
    void branchesOverlapAndTheJoinWaitsForBoth() throws Exception {
        CompiledGraph<AgentState> graph = buildGraph();
        ExecutorService branchThreads = Executors.newFixedThreadPool(2);
        ExecutorService graphThread = Executors.newSingleThreadExecutor();
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .addParallelNodeExecutor("plan", branchThreads)
                    .build();
            Future<Optional<AgentState>> run = graphThread.submit(() -> graph.invoke(Map.of(), config));

            // Overlap: each task only gets past its start while the other one is also running.
            assertThat(bothStarted.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("both tasks were running at the same time")
                    .isTrue();
            assertThat(fastTaskFinished.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the fast task finished")
                    .isTrue();

            // Join: one branch is done, the other is still held, so the next stage must not start.
            // The grace wait can only let a broken join slip through, never fail a correct one.
            assertThat(joinStarted.await(GRACE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the join started while a branch was still running")
                    .isFalse();

            slowTaskMayFinish.countDown();
            AgentState finalState = run.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS).orElseThrow();

            assertThat(finalState.<List<String>>value("joined").orElseThrow())
                    .containsExactlyInAnyOrder("fast-task", "slow-task");
            assertThat(indexOf("fast-task:start")).isLessThan(indexOf("slow-task:finish"));
            assertThat(indexOf("slow-task:start")).isLessThan(indexOf("fast-task:finish"));
            assertThat(indexOf("join:start"))
                    .isGreaterThan(indexOf("fast-task:finish"))
                    .isGreaterThan(indexOf("slow-task:finish"));
        } finally {
            slowTaskMayFinish.countDown();
            branchThreads.shutdownNow();
            graphThread.shutdownNow();
        }
    }

    @Test
    void aTaskThatCrashesFailsTheRunAndTheJoinNeverRuns() throws Exception {
        CompiledGraph<AgentState> graph = buildGraph(
                "passing-task", task("passing-task", null, fastTaskFinished),
                "failing-task", failingTask("failing-task", "failing-task crashed"));
        ExecutorService branchThreads = Executors.newFixedThreadPool(2);
        try {
            RunnableConfig config = RunnableConfig.builder()
                    .addParallelNodeExecutor("plan", branchThreads)
                    .build();

            // The run fails, and says why, instead of returning a state as if every branch had passed.
            assertThatThrownBy(() -> graph.invoke(Map.of(), config))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("failing-task crashed");

            // It really was one branch passing next to one crashing, not a branch that never ran.
            assertThat(fastTaskFinished.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the other task passed")
                    .isTrue();
            assertThat(events).contains("passing-task:finish", "failing-task:crash");

            // The next stage never ran, not even after the passing branch finished.
            // The grace wait can only let a broken join slip through, never fail a correct one.
            assertThat(joinStarted.await(GRACE.toMillis(), TimeUnit.MILLISECONDS))
                    .as("the join started although a branch crashed")
                    .isFalse();
            assertThat(events).doesNotContain("join:start");
        } finally {
            branchThreads.shutdownNow();
        }
    }

    private CompiledGraph<AgentState> buildGraph() throws GraphStateException {
        return buildGraph(
                "fast-task", task("fast-task", null, fastTaskFinished),
                "slow-task", task("slow-task", slowTaskMayFinish, null));
    }

    /** {@code plan} fans out to the two named tasks, and {@code join} runs after both. */
    private CompiledGraph<AgentState> buildGraph(
            String firstName, NodeAction<AgentState> first, String secondName, NodeAction<AgentState> second)
            throws GraphStateException {
        Map<String, Channel<?>> channels = Map.of("results", Channels.appender(ArrayList::new));
        return new StateGraph<>(channels, AgentState::new)
                .addNode("plan", node_async(state -> Map.of()))
                .addNode(firstName, node_async(first))
                .addNode(secondName, node_async(second))
                .addNode("join", node_async(this::join))
                .addEdge(START, "plan")
                .addEdge("plan", firstName)
                .addEdge("plan", secondName)
                .addEdge(firstName, "join")
                .addEdge(secondName, "join")
                .addEdge("join", END)
                .compile();
    }

    /**
     * A fake task. It reports that it started, then waits for the other task to start too, which
     * only happens if the two overlap. {@code holdUntil} keeps it running; {@code onFinish} tells
     * the test it is done.
     */
    private NodeAction<AgentState> task(String name, CountDownLatch holdUntil, CountDownLatch onFinish) {
        return state -> {
            events.add(name + ":start");
            bothStarted.countDown();
            if (!bothStarted.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(name + " never saw the other task start: not parallel");
            }
            if (holdUntil != null && !holdUntil.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(name + " was never released");
            }
            events.add(name + ":finish");
            if (onFinish != null) {
                onFinish.countDown();
            }
            return Map.of("results", name);
        };
    }

    /** A fake task that is running alongside the other one when it crashes. */
    private NodeAction<AgentState> failingTask(String name, String message) {
        return state -> {
            events.add(name + ":start");
            bothStarted.countDown();
            if (!bothStarted.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(name + " never saw the other task start: not parallel");
            }
            events.add(name + ":crash");
            throw new IllegalStateException(message);
        };
    }

    private Map<String, Object> join(AgentState state) {
        events.add("join:start");
        joinStarted.countDown();
        return Map.of("joined", state.<List<String>>value("results").orElseThrow());
    }

    private int indexOf(String event) {
        int index = events.indexOf(event);
        assertThat(index).as("event %s was recorded in %s", event, events).isGreaterThanOrEqualTo(0);
        return index;
    }
}
