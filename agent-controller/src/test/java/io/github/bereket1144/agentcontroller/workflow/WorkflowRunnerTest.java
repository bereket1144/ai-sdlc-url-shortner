package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

class WorkflowRunnerTest {

    @Test
    @DisplayName("AC-FLOW-1.3 the first node of the active graph reads the run ID and requirement it was given")
    void activeGraphReceivesRunInput() throws GraphStateException {
        UUID runId = UUID.randomUUID();
        String requirement = "Build a task tracker";

        AgentState result = new WorkflowRunner(new DummySdlcNodes()).run(runId, requirement, RunKind.GREENFIELD);

        assertThat(result.<UUID>value("runId")).contains(runId);
        assertThat(result.<String>value("requirement")).contains(requirement);
        assertThat(result.<String>value("receipt")).contains(runId + ":" + requirement);
    }

    @Test
    @DisplayName("AC-FLOW-1.1, 1.2, 1.3 greenfield stages run once in order, skip impact analysis, and add one value each without losing earlier state")
    void greenfieldStagesPassStateInOrder() throws GraphStateException {
        DummySdlcNodes nodes = spy(new DummySdlcNodes());
        UUID runId = UUID.randomUUID();
        String requirement = "Build a task tracker";

        AgentState result = new WorkflowRunner(nodes).run(runId, requirement, RunKind.GREENFIELD);

        assertThat(result.<UUID>value("runId")).contains(runId);
        assertThat(result.<String>value("requirement")).contains(requirement);
        assertThat(result.<RunKind>value("kind")).contains(RunKind.GREENFIELD);
        for (String role : new String[] {"requirements", "design", "planning", "development",
                "qa", "review", "documentation"}) {
            assertThat(result.<String>value(role)).contains(role + ":done");
        }
        assertThat(result.<String>value("impactAnalysis")).isEmpty();
        assertThat(result.<Boolean>value("joined")).contains(true);

        verifyEachNodeOnceInOrder(nodes, false);
    }

    @Test
    @DisplayName("AC-FLOW-1.2, 1.3 brownfield impact analysis runs once between requirements and design and every value reaches the end")
    void brownfieldIncludesImpactAnalysis() throws GraphStateException {
        DummySdlcNodes nodes = spy(new DummySdlcNodes());
        UUID runId = UUID.randomUUID();
        String requirement = "Add task deadlines";

        AgentState result = new WorkflowRunner(nodes).run(runId, requirement, RunKind.BROWNFIELD);

        assertThat(result.<UUID>value("runId")).contains(runId);
        assertThat(result.<String>value("requirement")).contains(requirement);
        assertThat(result.<RunKind>value("kind")).contains(RunKind.BROWNFIELD);
        for (String role : new String[] {"requirements", "impactAnalysis", "design", "planning",
                "development", "qa", "review", "documentation"}) {
            assertThat(result.<String>value(role)).contains(role + ":done");
        }
        assertThat(result.<Boolean>value("joined")).contains(true);
        verifyEachNodeOnceInOrder(nodes, true);
    }

    @Test
    @DisplayName("R-9 a missing run ID is rejected with a clear message")
    void rejectsNullRunId() throws GraphStateException {
        WorkflowRunner runner = new WorkflowRunner(new DummySdlcNodes());

        assertThatNullPointerException()
                .isThrownBy(() -> runner.run(null, "Build a task tracker", RunKind.GREENFIELD))
                .withMessage("runId must not be null");
    }

    @Test
    @DisplayName("R-9 a missing requirement is rejected with a clear message")
    void rejectsNullRequirement() throws GraphStateException {
        WorkflowRunner runner = new WorkflowRunner(new DummySdlcNodes());
        UUID runId = UUID.randomUUID();

        assertThatNullPointerException()
                .isThrownBy(() -> runner.run(runId, null, RunKind.GREENFIELD))
                .withMessage("requirement must not be null");
    }

    @Test
    @DisplayName("a missing workflow kind is rejected before routing")
    void rejectsNullKind() throws GraphStateException {
        WorkflowRunner runner = new WorkflowRunner(new DummySdlcNodes());
        UUID runId = UUID.randomUUID();

        assertThatNullPointerException()
                .isThrownBy(() -> runner.run(runId, "Build a task tracker", null))
                .withMessage("kind must not be null");
    }

    @ParameterizedTest(name = "{0} without {2}")
    @MethodSource("nodesWithAMissingPredecessorValue")
    @DisplayName("AC-FLOW-1.2, 1.3 a node fails with a clear message when a value from its predecessors is missing")
    void nodeRejectsMissingPredecessorValue(String node, Function<AgentState, Map<String, Object>> action,
            Map<String, Object> present, String missing) {
        Map<String, Object> state = new HashMap<>(Map.of("runId", UUID.randomUUID(),
                "requirement", "Build a task tracker", "kind", RunKind.GREENFIELD));
        state.putAll(present);

        assertThatIllegalStateException()
                .isThrownBy(() -> action.apply(new AgentState(state)))
                .withMessage("Missing workflow state: " + missing);
    }

    @Test
    @DisplayName("AC-FLOW-1.2 brownfield design fails when impact analysis did not run")
    void brownfieldDesignRejectsMissingImpactAnalysis() {
        DummySdlcNodes nodes = new DummySdlcNodes();
        AgentState state = new AgentState(Map.of("runId", UUID.randomUUID(),
                "requirement", "Change an existing app", "kind", RunKind.BROWNFIELD,
                "requirements", "requirements:done"));

        assertThatIllegalStateException()
                .isThrownBy(() -> nodes.design(state))
                .withMessage("Missing workflow state: impactAnalysis");
    }

    private static Stream<Arguments> nodesWithAMissingPredecessorValue() {
        DummySdlcNodes nodes = new DummySdlcNodes();
        Map<String, Object> none = Map.of();
        return Stream.of(
                Arguments.of("impactAnalysis", (Function<AgentState, Map<String, Object>>) nodes::impactAnalysis,
                        none, "requirements"),
                Arguments.of("design", (Function<AgentState, Map<String, Object>>) nodes::design,
                        none, "requirements"),
                Arguments.of("planning", (Function<AgentState, Map<String, Object>>) nodes::planning,
                        none, "design"),
                Arguments.of("development", (Function<AgentState, Map<String, Object>>) nodes::development,
                        none, "planning"),
                Arguments.of("qa", (Function<AgentState, Map<String, Object>>) nodes::qa,
                        none, "development"),
                Arguments.of("review", (Function<AgentState, Map<String, Object>>) nodes::review,
                        none, "development"),
                Arguments.of("documentation", (Function<AgentState, Map<String, Object>>) nodes::documentation,
                        none, "development"),
                Arguments.of("collect", (Function<AgentState, Map<String, Object>>) nodes::collect,
                        none, "qa"),
                Arguments.of("collect", (Function<AgentState, Map<String, Object>>) nodes::collect,
                        Map.of("qa", "qa:done"), "review"),
                Arguments.of("collect", (Function<AgentState, Map<String, Object>>) nodes::collect,
                        Map.of("qa", "qa:done", "review", "review:done"), "documentation"));
    }

    @Test
    @DisplayName("AC-EXEC-5.1, AC-FLOW-1.4 a node of the real graph that throws makes run() throw with the original exception as root cause")
    void failingNodeReachesTheCallerWithItsOriginalCause() throws GraphStateException {
        DummySdlcNodes nodes = spy(new DummySdlcNodes());
        IllegalStateException failure = new IllegalStateException("QA broke");
        doThrow(failure).when(nodes).qa(any());
        WorkflowRunner runner = new WorkflowRunner(nodes);

        assertThatThrownBy(() -> runner.run(UUID.randomUUID(), "Build a task tracker", RunKind.GREENFIELD))
                .rootCause().isSameAs(failure);
    }

    @Test
    @DisplayName("AC-EXEC-5.1, AC-FLOW-1.4 a node of the real graph that throws an Error also reaches the caller with the Error as root cause")
    void failingNodeErrorReachesTheCallerWithItsOriginalCause() throws GraphStateException {
        DummySdlcNodes nodes = spy(new DummySdlcNodes());
        AssertionError failure = new AssertionError("QA broke");
        doThrow(failure).when(nodes).qa(any());
        WorkflowRunner runner = new WorkflowRunner(nodes);

        assertThatThrownBy(() -> runner.run(UUID.randomUUID(), "Build a task tracker", RunKind.GREENFIELD))
                .rootCause().isSameAs(failure);
    }

    @Test
    @DisplayName("AC-FLOW-1.4 a failing node is logged as workflow_failed with the run ID, kind and class name only")
    void failureIsLoggedWithoutMessages() throws GraphStateException {
        DummySdlcNodes nodes = spy(new DummySdlcNodes());
        UUID runId = UUID.randomUUID();
        doThrow(new IllegalStateException("secret failure detail")).when(nodes).requirements(any());
        WorkflowRunner runner = new WorkflowRunner(nodes);

        Logger runnerLog = (Logger) LoggerFactory.getLogger(WorkflowRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runnerLog.addAppender(appender);
        try {
            assertThatThrownBy(() -> runner.run(runId, "Private requirement text", RunKind.BROWNFIELD))
                    .isInstanceOf(RuntimeException.class);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .filteredOn(message -> message.startsWith("event=workflow_failed"))
                    .singleElement()
                    .asString()
                    .startsWith("event=workflow_failed runId=" + runId + " kind=BROWNFIELD errorType=")
                    .doesNotContain("secret failure detail", "Private requirement text");
        } finally {
            runnerLog.detachAppender(appender);
            appender.stop();
        }
    }

    // Every node runs exactly once (impact analysis only for brownfield) and the route is strictly ordered:
    // requirements, [impact analysis], design, planning, development, then each of the three branches, then collect.
    private static void verifyEachNodeOnceInOrder(DummySdlcNodes nodes, boolean brownfield) {
        verify(nodes, times(1)).initialize(any());
        verify(nodes, times(1)).requirements(any());
        verify(nodes, times(brownfield ? 1 : 0)).impactAnalysis(any());
        verify(nodes, times(1)).design(any());
        verify(nodes, times(1)).planning(any());
        verify(nodes, times(1)).development(any());
        verify(nodes, times(1)).qa(any());
        verify(nodes, times(1)).review(any());
        verify(nodes, times(1)).documentation(any());
        verify(nodes, times(1)).collect(any());

        InOrder spine = inOrder(nodes);
        spine.verify(nodes).initialize(any());
        spine.verify(nodes).requirements(any());
        if (brownfield) {
            spine.verify(nodes).impactAnalysis(any());
        }
        spine.verify(nodes).design(any());
        spine.verify(nodes).planning(any());
        spine.verify(nodes).development(any());

        // The three branches may finish in any order, so each one is checked against development and collect alone.
        InOrder qa = inOrder(nodes);
        qa.verify(nodes).development(any());
        qa.verify(nodes).qa(any());
        qa.verify(nodes).collect(any());
        InOrder review = inOrder(nodes);
        review.verify(nodes).development(any());
        review.verify(nodes).review(any());
        review.verify(nodes).collect(any());
        InOrder documentation = inOrder(nodes);
        documentation.verify(nodes).development(any());
        documentation.verify(nodes).documentation(any());
        documentation.verify(nodes).collect(any());
    }
}
