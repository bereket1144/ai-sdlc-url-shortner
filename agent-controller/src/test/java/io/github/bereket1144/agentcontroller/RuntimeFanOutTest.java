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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

/**
 * Shows that the number of parallel tasks can be decided while the workflow runs, with fake tasks
 * and no model calls. The graph is fixed: {@code planner -> executor -> reporter}. A fake planner
 * decides how many tasks there are, the {@code executor} node runs all of them side by side, and
 * the {@code reporter} stage gets every result. A task that crashes fails the run and the
 * reporter never runs.
 *
 * <p>The fan-out is plain Java inside one node because LangGraph4j 1.8.27 cannot do it with graph
 * edges: parallel branches are separate {@code addEdge} calls made before the graph is compiled, a
 * conditional edge and a {@code Command} each name a single next node, and conditional edges are
 * rejected on a parallel node. See {@code docs/adr/0001-run-the-task-scheduler-inside-one-node.md}.
 */
class RuntimeFanOutTest {

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private volatile CountDownLatch allStarted;
    private volatile String crashingTask; // the planned task that throws, or null for none

    @Test
    void oneFixedGraphRunsAsManyTasksAsThePlannerDecidesAndJoinsTheirResults() throws Exception {
        CompiledGraph<AgentState> graph = buildGraph(); // built once; only the input changes

        for (int taskCount : List.of(3, 5)) {
            events.clear();
            allStarted = new CountDownLatch(taskCount);

            AgentState finalState = graph.invoke(Map.of("taskCount", taskCount)).orElseThrow();

            List<String> expectedTasks = IntStream.rangeClosed(1, taskCount).mapToObj(i -> "task-" + i).toList();
            List<String> expectedResults = expectedTasks.stream().map(task -> task + ":done").toList();

            // The planner decided the tasks, and the results came back for every one, in plan order.
            assertThat(finalState.<List<String>>value("tasks").orElseThrow()).isEqualTo(expectedTasks);
            assertThat(finalState.<List<String>>value("report").orElseThrow()).isEqualTo(expectedResults);

            // Side by side: every task had started before any task finished.
            int lastStart = expectedTasks.stream().mapToInt(task -> indexOf(task + ":start")).max().orElseThrow();
            int firstFinish = expectedTasks.stream().mapToInt(task -> indexOf(task + ":finish")).min().orElseThrow();
            assertThat(lastStart).as("%d tasks, events %s", taskCount, events).isLessThan(firstFinish);

            // Joined: the reporter ran once, after the last task finished.
            int lastFinish = expectedTasks.stream().mapToInt(task -> indexOf(task + ":finish")).max().orElseThrow();
            assertThat(events.stream().filter("reporter:start"::equals)).hasSize(1);
            assertThat(indexOf("reporter:start")).isGreaterThan(lastFinish);
        }
    }

    @Test
    void aTaskThatCrashesInsideTheSchedulerFailsTheRunAndTheReporterNeverRuns() throws Exception {
        CompiledGraph<AgentState> graph = buildGraph();
        allStarted = new CountDownLatch(3);
        crashingTask = "task-2";

        // The run fails, and says why, instead of returning the results of the tasks that did finish.
        assertThatThrownBy(() -> graph.invoke(Map.of("taskCount", 3)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("task-2 crashed");

        // All three were running side by side; two finished and one crashed.
        assertThat(events).contains("task-1:finish", "task-2:crash", "task-3:finish");
        assertThat(events).doesNotContain("task-2:finish");

        // The next stage never ran.
        assertThat(events).doesNotContain("reporter:start");
    }

    private CompiledGraph<AgentState> buildGraph() throws GraphStateException {
        return new StateGraph<>(AgentState::new)
                .addNode("planner", node_async(this::plan))
                .addNode("executor", node_async(this::executeAll))
                .addNode("reporter", node_async(this::report))
                .addEdge(START, "planner")
                .addEdge("planner", "executor")
                .addEdge("executor", "reporter")
                .addEdge("reporter", END)
                .compile();
    }

    /** A fake planner: it makes as many tasks as the input asks for. */
    private Map<String, Object> plan(AgentState state) {
        int taskCount = state.<Integer>value("taskCount").orElseThrow();
        return Map.of("tasks", IntStream.rangeClosed(1, taskCount).mapToObj(i -> "task-" + i).toList());
    }

    /**
     * A fake scheduler: starts every planned task on its own thread and returns only when all have
     * finished. One thread per task keeps this test about the count; a real scheduler would cap the
     * threads and run the rest as others finish.
     */
    private Map<String, Object> executeAll(AgentState state) throws Exception {
        List<String> tasks = state.<List<String>>value("tasks").orElseThrow();
        ExecutorService threads = Executors.newFixedThreadPool(tasks.size());
        try {
            List<CompletableFuture<String>> running = tasks.stream()
                    .map(task -> CompletableFuture.supplyAsync(() -> runTask(task), threads))
                    .toList();
            CompletableFuture.allOf(running.toArray(CompletableFuture[]::new))
                    .get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
            return Map.of("results", running.stream().map(CompletableFuture::join).toList());
        } finally {
            threads.shutdownNow();
        }
    }

    /** A fake task: it only gets past its start while every other planned task is also running. */
    private String runTask(String task) {
        events.add(task + ":start");
        allStarted.countDown();
        try {
            if (!allStarted.await(PATIENCE.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(task + " never saw the other tasks start: not side by side");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(task + " was interrupted", e);
        }
        if (task.equals(crashingTask)) {
            events.add(task + ":crash");
            throw new IllegalStateException(task + " crashed");
        }
        events.add(task + ":finish");
        return task + ":done";
    }

    private Map<String, Object> report(AgentState state) {
        events.add("reporter:start");
        return Map.of("report", state.<List<String>>value("results").orElseThrow());
    }

    private int indexOf(String event) {
        int index = events.indexOf(event);
        assertThat(index).as("event %s was recorded in %s", event, events).isGreaterThanOrEqualTo(0);
        return index;
    }
}
