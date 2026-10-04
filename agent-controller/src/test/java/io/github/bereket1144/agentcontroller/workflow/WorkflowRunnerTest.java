package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WorkflowRunnerTest {

    @Test
    @DisplayName("AC-FLOW-1.3 the first node of the active graph reads the run ID and requirement it was given")
    void activeGraphReceivesRunInput() throws GraphStateException {
        UUID runId = UUID.randomUUID();
        String requirement = "Build a task tracker";

        AgentState result = new WorkflowRunner(new DummySdlcNodes()).run(runId, requirement);

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

        AgentState result = new WorkflowRunner(nodes).run(runId, requirement);

        assertThat(result.<UUID>value("runId")).contains(runId);
        assertThat(result.<String>value("requirement")).contains(requirement);
        assertThat(result.<RunKind>value("kind")).contains(RunKind.GREENFIELD);
        for (String role : new String[] {"requirements", "design", "planning", "development",
                "qa", "review", "documentation"}) {
            assertThat(result.<String>value(role)).contains(role + ":done");
        }
        assertThat(result.<String>value("impactAnalysis")).isEmpty();
        assertThat(result.<Boolean>value("joined")).contains(true);

        InOrder order = inOrder(nodes);
        order.verify(nodes).requirements(any());
        order.verify(nodes).design(any());
        order.verify(nodes).planning(any());
        order.verify(nodes).development(any());
        verify(nodes).qa(any());
        verify(nodes).review(any());
        verify(nodes).documentation(any());
        verify(nodes, never()).impactAnalysis(any());
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
        InOrder order = inOrder(nodes);
        order.verify(nodes).requirements(any());
        order.verify(nodes).impactAnalysis(any());
        order.verify(nodes).design(any());
    }

    @Test
    @DisplayName("R-9 a missing run ID is rejected with a clear message")
    void rejectsNullRunId() throws GraphStateException {
        WorkflowRunner runner = new WorkflowRunner(new DummySdlcNodes());

        assertThatNullPointerException()
                .isThrownBy(() -> runner.run(null, "Build a task tracker"))
                .withMessage("runId must not be null");
    }

    @Test
    @DisplayName("R-9 a missing requirement is rejected with a clear message")
    void rejectsNullRequirement() throws GraphStateException {
        WorkflowRunner runner = new WorkflowRunner(new DummySdlcNodes());
        UUID runId = UUID.randomUUID();

        assertThatNullPointerException()
                .isThrownBy(() -> runner.run(runId, null))
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

    @Test
    @DisplayName("AC-FLOW-1.3 a node fails with a clear message when a value from its predecessors is missing")
    void nodeRejectsMissingPredecessorValue() {
        DummySdlcNodes nodes = new DummySdlcNodes();

        assertThatIllegalStateException()
                .isThrownBy(() -> nodes.design(new AgentState(Map.of())))
                .withMessage("Missing workflow state: requirements");
    }
}
