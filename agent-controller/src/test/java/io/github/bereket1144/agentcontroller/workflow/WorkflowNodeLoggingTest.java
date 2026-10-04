package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class WorkflowNodeLoggingTest {

    private static final String REQUIREMENT = "Distinctive private requirement ZX-4417";

    @Test
    @DisplayName("AC-FLOW-2.1, 2.2 greenfield logs every executed node and safe state markers")
    void greenfieldLogsNodeOutputsWithoutRequirementText() throws Exception {
        UUID runId = UUID.randomUUID();

        List<String> messages = captureNodeLogs(() ->
                new WorkflowRunner(new DummySdlcNodes()).run(runId, REQUIREMENT, RunKind.GREENFIELD));

        assertSafeNodeLogs(messages, runId, 9);
        assertThat(messages).noneMatch(message -> message.contains("node=impactAnalysis "));
        assertThat(logOf(messages, "planning")).contains("requirements=requirements:done", "design=design:done")
                .doesNotContain("impactAnalysis=");
        assertThat(logOf(messages, "collect")).doesNotContain("impactAnalysis=");
    }

    @Test
    @DisplayName("AC-FLOW-2.1, 2.2 brownfield also logs impact analysis and its marker")
    void brownfieldLogsImpactAnalysis() throws Exception {
        UUID runId = UUID.randomUUID();

        List<String> messages = captureNodeLogs(() ->
                new WorkflowRunner(new DummySdlcNodes()).run(runId, REQUIREMENT, RunKind.BROWNFIELD));

        assertSafeNodeLogs(messages, runId, 10);
        assertThat(logOf(messages, "impactAnalysis"))
                .contains("addedKeys=[impactAnalysis]", "requirements=requirements:done",
                        "impactAnalysis=impactAnalysis:done");
        assertThat(logOf(messages, "planning")).contains("requirements=requirements:done",
                "impactAnalysis=impactAnalysis:done", "design=design:done");
        assertThat(logOf(messages, "collect")).contains("impactAnalysis=impactAnalysis:done");
    }

    @Test
    @DisplayName("AC-FLOW-2.2 a role key that holds free text or a non-String value is never logged and does not fail the node")
    void roleKeyWithUnsafeValueIsNotLogged() throws Exception {
        UUID runId = UUID.randomUUID();
        AgentState state = new AgentState(Map.of("runId", runId, "requirement", REQUIREMENT,
                "kind", RunKind.GREENFIELD, "development", "development:done",
                "review", "free text with ZX-4417 inside", "planning", List.of("secret-list")));

        List<String> messages = captureNodeLogs(() -> new DummySdlcNodes().qa(state));

        assertThat(messages).singleElement().asString()
                .contains("node=qa", "development=development:done", "qa=qa:done")
                .doesNotContain("free text", "ZX-4417", "secret-list", "review=", "planning=");
    }

    // The checks that AC-FLOW-2.1 and 2.2 require for every run kind.
    private static void assertSafeNodeLogs(List<String> messages, UUID runId, int executedNodes) {
        assertThat(messages).hasSize(executedNodes);
        assertThat(messages).allMatch(message -> message.contains("runId=" + runId));
        assertThat(messages).noneMatch(message -> message.contains(REQUIREMENT));
        assertThat(messages).noneMatch(message -> message.contains(runId + ":" + REQUIREMENT));
        for (String node : List.of("requirements", "design", "planning", "development",
                "qa", "review", "documentation")) {
            assertThat(logOf(messages, node))
                    .contains("addedKeys=[" + node + "]")
                    .contains(node + "=" + node + ":done");
        }
        assertThat(logOf(messages, "initialize")).contains("addedKeys=[receipt] markers={}");
        // Inherited markers: a branch node still shows what its predecessors added.
        assertThat(logOf(messages, "qa")).contains("development=development:done");
        assertThat(logOf(messages, "collect")).contains("addedKeys=[joined]")
                .contains("qa=qa:done", "review=review:done", "documentation=documentation:done");
    }

    private static String logOf(List<String> messages, String node) {
        return messages.stream().filter(message -> message.contains("node=" + node + " "))
                .findFirst().orElseThrow(() -> new AssertionError("no log for node " + node));
    }

    private static List<String> captureNodeLogs(ThrowingRunnable action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DummySdlcNodes.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        // QA, review, and documentation can write to this appender from different threads.
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
