package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.workflow.WorkflowRunner;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(TestcontainersConfiguration.class)
// The 25 ms poll must stop when this class ends, or it keeps polling in Spring's test cache.
@DirtiesContext
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

    private final List<UUID> createdIds = new ArrayList<>();

    private final CountDownLatch releaseWorkflow = new CountDownLatch(1);

    @AfterEach
    void deleteCreatedRuns() {
        // Never leave a blocked workflow behind; it would hold the scheduler thread.
        releaseWorkflow.countDown();
        createdIds.forEach(runRepository::deleteById);
    }

    private UUID createRun(String requirement) {
        UUID id = runService.createRun(requirement).getId();
        createdIds.add(id);
        return id;
    }

    @Test
    @DisplayName("AC-EXEC-4.2, 4.3 polling starts a newly submitted run once")
    void scheduledPollingStartsNewRunOnlyOnce() throws InterruptedException {
        String requirement = "Build a task tracker";
        when(workflowRunner.run(any(UUID.class), eq(requirement), eq(RunKind.GREENFIELD), isNull()))
                .thenReturn(new AgentState(Map.<String, Object>of("receipt", "completed")));
        UUID createdId = createRun(requirement);

        awaitStatus(createdId, RunStatus.SUCCEEDED);

        // Trigger two later polls explicitly so the duplicate check does not depend on timing.
        runScheduler.poll();
        runScheduler.poll();
        verify(workflowRunner, times(1)).run(createdId, requirement, RunKind.GREENFIELD, null);
    }

    @Test
    @DisplayName("AC-EXEC-4.3 a poll while a run is RUNNING does not start it again")
    void pollDuringRunningWorkflowDoesNotStartRunAgain() throws InterruptedException {
        String requirement = "Build a task tracker";
        CountDownLatch workflowEntered = new CountDownLatch(1);
        when(workflowRunner.run(any(UUID.class), eq(requirement), eq(RunKind.GREENFIELD), isNull())).thenAnswer(invocation -> {
            workflowEntered.countDown();
            releaseWorkflow.await(5, TimeUnit.SECONDS);
            return new AgentState(Map.<String, Object>of("receipt", "completed"));
        });
        UUID runId = createRun(requirement);
        assertThat(workflowEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(runService.getRun(runId).getStatus()).isEqualTo(RunStatus.RUNNING);

        // The scheduler thread is held by the workflow, so poll from the test thread: it must find nothing.
        runScheduler.poll();
        runScheduler.poll();
        verify(workflowRunner, times(1)).run(runId, requirement, RunKind.GREENFIELD, null);

        releaseWorkflow.countDown();
        awaitStatus(runId, RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("AC-EXEC-5.1, 4.3 a failing poll does not stop the next poll and its failed run is not started again")
    void failingPollDoesNotStopNextPoll() throws InterruptedException {
        String failingRequirement = "Build a failing tracker";
        String laterRequirement = "Build a later tracker";
        when(workflowRunner.run(any(UUID.class), eq(failingRequirement), eq(RunKind.GREENFIELD), isNull()))
                .thenThrow(new IllegalStateException("workflow broke"));
        when(workflowRunner.run(any(UUID.class), eq(laterRequirement), eq(RunKind.GREENFIELD), isNull()))
                .thenReturn(new AgentState(Map.<String, Object>of("receipt", "completed")));
        UUID failedId = createRun(failingRequirement);
        awaitStatus(failedId, RunStatus.FAILED);

        // The exception escaped the poll that ran the first run; a run created afterwards still gets started.
        UUID laterId = createRun(laterRequirement);
        awaitStatus(laterId, RunStatus.SUCCEEDED);

        verify(workflowRunner, times(1)).run(failedId, failingRequirement, RunKind.GREENFIELD, null);
        assertThat(runService.getRun(failedId).getStatus()).isEqualTo(RunStatus.FAILED);
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
