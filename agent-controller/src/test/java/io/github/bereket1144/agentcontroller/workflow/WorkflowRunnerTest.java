package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;

import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkflowRunnerTest {

    @Test
    @DisplayName("AC-EXEC-1.1 the node uses the claimed run ID and requirement")
    void executesOneNodeWithRunInput() throws GraphStateException {
        UUID runId = UUID.randomUUID();
        String requirement = "Build a task tracker";

        AgentState result = new WorkflowRunner().run(runId, requirement);

        assertThat(result.<String>value("receipt"))
                .contains(runId + ":" + requirement);
    }

    @Test
    @DisplayName("R-9 a missing run ID is rejected with a clear message")
    void rejectsNullRunId() throws GraphStateException {
        WorkflowRunner runner = new WorkflowRunner();

        assertThatNullPointerException()
                .isThrownBy(() -> runner.run(null, "Build a task tracker"))
                .withMessage("runId must not be null");
    }

    @Test
    @DisplayName("R-9 a missing requirement is rejected with a clear message")
    void rejectsNullRequirement() throws GraphStateException {
        WorkflowRunner runner = new WorkflowRunner();
        UUID runId = UUID.randomUUID();

        assertThatNullPointerException()
                .isThrownBy(() -> runner.run(runId, null))
                .withMessage("requirement must not be null");
    }
}
