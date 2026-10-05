package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The graph of the runner on the dummy nodes, started at the point where a retry says. */
class WorkflowRetryTest {

    private static final String REQUIREMENT = "Build a task tracker";

    private final DummySdlcNodes nodes = spy(new DummySdlcNodes());
    private final StageProgress progress = mock(StageProgress.class);
    private final RunResume resume = mock(RunResume.class);
    private final UUID runId = UUID.randomUUID();

    @Test
    @DisplayName("AC-AGT-66.2 a retry that starts at a stage runs that stage and the ones after it, with the stage "
            + "states kept, and the stages before it do not run again")
    void aRetryStartsAtItsStage() throws Exception {
        given("development", "requirements", "design", "planning");

        AgentState result = runner().retry(runId, REQUIREMENT, RunKind.GREENFIELD, "development");

        assertThat(result.<Boolean>value("joined")).contains(true);
        verify(nodes, never()).initialize(any());
        verify(nodes, never()).requirements(any());
        verify(nodes, never()).design(any());
        verify(nodes, never()).planning(any());
        verify(nodes).development(any());
        verify(nodes).qa(any());
        verify(nodes).review(any());
        verify(nodes).documentation(any());
        verify(progress, never()).reset(any());
    }

    @Test
    @DisplayName("AC-AGT-66.2 a retry that starts at one of QA, review and documentation runs the three as a group, "
            + "after development and before the join")
    void aRetryResumesTheGroupAsAGroup() throws Exception {
        given("review", "requirements", "design", "planning", "development");

        AgentState result = runner().retry(runId, REQUIREMENT, RunKind.GREENFIELD, "review");

        assertThat(result.<Boolean>value("joined")).contains(true);
        verify(nodes, never()).development(any());
        verify(nodes).qa(any());
        verify(nodes).review(any());
        verify(nodes).documentation(any());
        verify(nodes).collect(any());
    }

    @Test
    @DisplayName("AC-AGT-66.3 a retry from the beginning shows every stage as not started and runs the whole graph")
    void aRetryFromTheBeginningRunsEverything() throws Exception {
        given(RunResume.START);

        AgentState result = runner().retry(runId, REQUIREMENT, RunKind.GREENFIELD, RunResume.START);

        assertThat(result.<Boolean>value("joined")).contains(true);
        verify(progress).reset(runId);
        verify(nodes).initialize(any());
        verify(nodes).requirements(any());
        verify(nodes).design(any());
        verify(nodes).planning(any());
        verify(nodes).development(any());
    }

    @Test
    @DisplayName("AC-AGT-66.2 a runner built without the run resume refuses a retry")
    void aRetryNeedsTheRunResume() throws Exception {
        WorkflowRunner withoutResume = new WorkflowRunner(nodes);

        assertThatThrownBy(() -> withoutResume.retry(runId, REQUIREMENT, RunKind.GREENFIELD, "development"))
                .isInstanceOf(IllegalStateException.class).hasMessage("A retry needs the run resume");
    }

    /** The stand-in run resume hands the graph the markers that the dummy nodes leave for the stages before the start. */
    private void given(String startAt, String... earlierStages) {
        Map<String, Object> input = new HashMap<>(Map.of("runId", runId, "requirement", REQUIREMENT,
                "kind", RunKind.GREENFIELD));
        for (String stage : earlierStages) {
            input.put(stage, stage + ":done");
        }
        when(resume.input(runId, REQUIREMENT, RunKind.GREENFIELD, startAt)).thenReturn(input);
    }

    private WorkflowRunner runner() throws GraphStateException {
        return new WorkflowRunner(nodes, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(progress),
                Optional.empty(), 2, Optional.of(resume));
    }
}
