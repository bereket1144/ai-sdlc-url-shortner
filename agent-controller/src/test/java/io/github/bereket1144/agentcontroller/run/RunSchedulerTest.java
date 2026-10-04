package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "run.dispatch.polling-enabled=true",
        "run.dispatch.poll-delay-ms=25"
})
class RunSchedulerTest {

    @Autowired
    private RunService runService;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RunScheduler runScheduler;

    @MockitoBean
    private WorkflowRunner workflowRunner;

    private UUID createdId;

    @AfterEach
    void deleteCreatedRun() {
        if (createdId != null) {
            runRepository.deleteById(createdId);
        }
    }

    @Test
    @DisplayName("AC-EXEC-4.2, 4.3 polling starts a newly submitted run once")
    void scheduledPollingStartsNewRunOnlyOnce() throws InterruptedException {
        String requirement = "Build a task tracker";
        when(workflowRunner.run(any(UUID.class), eq(requirement)))
                .thenReturn(new AgentState(Map.<String, Object>of("receipt", "completed")));
        createdId = runService.createRun(requirement).getId();

        awaitStatus(createdId, RunStatus.SUCCEEDED);

        // Trigger two later polls explicitly so the duplicate check does not depend on timing.
        runScheduler.poll();
        runScheduler.poll();
        verify(workflowRunner, times(1)).run(createdId, requirement);
    }

    private void awaitStatus(UUID runId, RunStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (runService.getRun(runId).getStatus() == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(runService.getRun(runId).getStatus()).isEqualTo(expected);
    }
}
