package io.github.bereket1144.agentcontroller;

import java.util.Map;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AgentState;

import io.github.bereket1144.agentcontroller.checkpoint.CheckpointSaverFactory;

/**
 * The program {@link ProcessRestartTest} starts as its own JVM. It talks to the test only through
 * its arguments and the lines it prints, so nothing in memory is shared with the test or with
 * another run of this program.
 *
 * <p>Arguments: {@code mode runId host port database user password}. Modes:
 * <ul>
 *   <li>{@code pause}: run up to the approval gate, say {@code PAUSED}, then wait to be killed.
 *   <li>{@code recover}: do not run anything; report where the saved run is waiting.
 * </ul>
 * Lines the test reads start with {@code PID }, {@code EXECUTED }, {@code PAUSED} or {@code RECOVERED }.
 */
public final class ApprovalRunProcess {

    private ApprovalRunProcess() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException("expected: mode runId host port database user password");
        }
        String mode = args[0];
        RunnableConfig config = RunnableConfig.builder().threadId(args[1]).build();
        var graph = ApprovalGraph.build(
                CheckpointSaverFactory.create(args[2], Integer.parseInt(args[3]), args[4], args[5], args[6]),
                task -> System.out.println("EXECUTED " + task));

        System.out.println("PID " + ProcessHandle.current().pid());
        switch (mode) {
            case "pause" -> {
                graph.invoke(Map.of(), config);
                System.out.println("PAUSED");
                Thread.sleep(Long.MAX_VALUE); // stay alive until the test kills this JVM
            }
            case "recover" -> {
                var saved = graph.getState(config);
                AgentState state = saved.state();
                System.out.println("RECOVERED next=" + saved.next() + " draft=" + state.value("draft").orElse("none"));
            }
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }
}
