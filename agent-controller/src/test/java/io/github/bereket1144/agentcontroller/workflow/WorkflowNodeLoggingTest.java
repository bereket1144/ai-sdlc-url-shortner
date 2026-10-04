package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bereket1144.agentcontroller.run.RunKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class WorkflowNodeLoggingTest {

    @Test
    @DisplayName("AC-FLOW-2.1, 2.2 greenfield logs every executed node and safe state markers")
    void greenfieldLogsNodeOutputsWithoutRequirementText() throws Exception {
        UUID runId = UUID.randomUUID();
        String requirement = "Private requirement 12345";

        List<String> messages = captureNodeLogs(() ->
                new WorkflowRunner(new DummySdlcNodes()).run(runId, requirement));

        assertThat(messages).hasSize(9);
        assertThat(messages).allMatch(message -> message.contains("runId=" + runId));
        assertThat(messages).noneMatch(message -> message.contains(requirement));
        for (String node : List.of("requirements", "design", "planning", "development",
                "qa", "review", "documentation")) {
            assertThat(messages).anySatisfy(message -> assertThat(message)
                    .contains("node=" + node + " addedKeys=[" + node + "]")
                    .contains(node + "=" + node + ":done"));
        }
        assertThat(messages).noneMatch(message -> message.contains("node=impactAnalysis "));
        assertThat(messages).anySatisfy(message -> assertThat(message)
                .contains("node=initialize addedKeys=[receipt] markers={}"));
        assertThat(messages).anySatisfy(message -> assertThat(message)
                .contains("node=collect addedKeys=[joined]")
                .contains("qa=qa:done", "review=review:done", "documentation=documentation:done"));
    }

    @Test
    @DisplayName("AC-FLOW-2.1 brownfield also logs impact analysis and its marker")
    void brownfieldLogsImpactAnalysis() throws Exception {
        UUID runId = UUID.randomUUID();

        List<String> messages = captureNodeLogs(() ->
                new WorkflowRunner(new DummySdlcNodes()).run(runId, "Change an existing app", RunKind.BROWNFIELD));

        assertThat(messages).hasSize(10);
        assertThat(messages).anySatisfy(message -> assertThat(message)
                .contains("node=impactAnalysis addedKeys=[impactAnalysis]")
                .contains("requirements=requirements:done", "impactAnalysis=impactAnalysis:done"));
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
