package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.model.ModelClient;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Real database and HTTP boundaries; the model can be paused to accept a stop on another thread. */
@SpringBootTest(properties = {"run.dispatch.polling-enabled=false", "agent.model-client.mode=scripted",
        "agent.stages.requirements=agent", "agent.stages.design=agent", "agent.stages.planning=agent",
        "agent.approval.required-for=design,planning"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RunStopFullStackTest.Scripted.class})
class RunStopFullStackTest {
    private static final String CLEAR = "{\"questions\":[],\"assumptions\":[]}";
    private static final String QUESTIONS = """
            {"questions":[{"id":"Q-1","text":"Which operations?","why":"Define scope"}],"assumptions":[]}
            """;
    private static final Map<String, String> ANSWERS = Map.of(
            "requirements", """
                    {"stories":[{"id":"S-1","title":"Calculate","role":"user","want":"a sum","benefit":"a result",
                    "acceptanceCriteria":[{"id":"S-1.1","text":"Two numbers can be added"}]}],
                    "openQuestions":[],"assumptions":[]}
                    """,
            "design", """
                    {"components":["calculator"],"interfaces":["add"],"dataFlow":"input to sum",
                    "decisions":["one process"],"alternatives":["external service"],"risks":["invalid input"],
                    "diagrams":["graph TD; Input-->Sum"]}
                    """,
            "planning", "{\"tasks\":[\"implement addition\"]}");
    private static final ConcurrentLinkedQueue<String> CALLS = new ConcurrentLinkedQueue<>();
    private static volatile boolean askQuestions;
    private static volatile CountDownLatch designEntered;
    private static volatile CountDownLatch designReleased;

    @TestConfiguration(proxyBeanMethods = false)
    static class Scripted {
        @Bean
        ModelClient scriptedModel() {
            return (role, profile, systemText, userText) -> {
                CALLS.add(role);
                if (role.equals("design") && designEntered != null) {
                    designEntered.countDown();
                    try {
                        if (!designReleased.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test did not release the model call");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Test model was interrupted", interrupted);
                    }
                }
                String content = role.equals("requirements") && userText.contains("CLARIFICATION phase")
                        ? askQuestions ? QUESTIONS : CLEAR : ANSWERS.get(role);
                return new ModelResult(content, FinishReason.COMPLETE, null, null, Duration.ZERO);
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RunService runs;
    @Autowired RunStop stops;
    @Autowired RunDispatcher dispatcher;
    @Autowired ArtifactStore artifacts;
    @Autowired ClarificationService clarification;
    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;
    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : created) {
            for (String table : List.of("approval_decision", "clarification_batch", "run_stage", "artifact",
                    "model_call", "model_budget_reservation", "verification_result")) {
                jdbc.update("delete from " + table + " where run_id = ?", id);
            }
            jdbc.update("delete from runs where id = ?", id);
        }
        CALLS.clear();
        askQuestions = false;
        designEntered = null;
        designReleased = null;
    }

    @Test
    @DisplayName("AC-AGT-43.1, 43.2 stopping a queued run is immediate, and the scheduler never claims it")
    void stopsBeforeDispatch() throws Exception {
        UUID id = newRun();

        mvc.perform(post("/v1/runs/" + id + "/stop")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STOPPED"));

        mvc.perform(get("/v1/runs/" + id)).andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
        assertThat(dispatcher.dispatchNext()).isFalse();
        assertThat(CALLS).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-43.1, 43.3, 43.4 a stop during a model call preserves its result and finished stages, "
            + "reopens the interrupted stage, and resume completes without spending an attempt")
    void stopsAnInFlightStageAndResumesIt() throws Exception {
        UUID id = newRun();
        designEntered = new CountDownLatch(1);
        designReleased = new CountDownLatch(1);
        // Latches prove the stop arrives during the model call, without sleeps or timing assumptions.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatched = executor.submit(dispatcher::dispatchNext);
            try {
                assertThat(designEntered.await(10, TimeUnit.SECONDS)).isTrue();
                mvc.perform(post("/v1/runs/" + id + "/stop")).andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("RUNNING"));
            } finally {
                designReleased.countDown();
            }
            assertThat(dispatched.get(15, TimeUnit.SECONDS)).isTrue();
        }
        designEntered = null;
        designReleased = null;

        mvc.perform(get("/v1/runs/" + id)).andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.failureReason").doesNotExist())
                .andExpect(jsonPath("$.stages[0].state").value("FINISHED"))
                .andExpect(jsonPath("$.stages[2].state").value("NOT_STARTED"));
        assertThat(artifacts.find(id, "design", 1)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from model_call where run_id = ?", Integer.class, id))
                .isEqualTo(3);

        mvc.perform(post("/v1/runs/" + id + "/resume")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"));
        assertThat(runs.getRun(id).getAttempt()).isEqualTo(1);
        assertThat(runs.getRun(id).getResumedFrom()).isEqualTo("design");
        dispatcher.dispatchNext();
        assertThat(approvals.pending(id).version()).isEqualTo(2);
        approve(id);
        dispatcher.dispatchNext();
        approve(id);
        dispatcher.dispatchNext();

        assertThat(runs.getRun(id).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(runs.getRun(id).getAttempt()).isEqualTo(1);
        assertThat(CALLS.stream().filter(role -> role.equals("requirements")).count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("AC-AGT-43.5 a stopped question or approval wait resumes into exactly the same request")
    void resumesTheSameHumanWait(boolean questions) throws Exception {
        askQuestions = questions;
        UUID id = newRun();
        dispatcher.dispatchNext();
        var savedQuestions = clarification.questions(id);
        var savedApproval = approvals.pending(id);
        int calls = CALLS.size();

        mvc.perform(post("/v1/runs/" + id + "/stop")).andExpect(status().isAccepted());
        mvc.perform(post("/v1/runs/" + id + "/resume")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_INPUT"));

        assertThat(clarification.questions(id)).isEqualTo(savedQuestions);
        assertThat(approvals.pending(id)).isEqualTo(savedApproval);
        assertThat(dispatcher.dispatchNext()).isFalse();
        assertThat(CALLS).hasSize(calls);
        mvc.perform(get("/v1/runs/" + id)).andExpect(jsonPath("$.status").value("WAITING_FOR_INPUT"));
    }

    @Test
    @DisplayName("AC-AGT-43.1, 43.4 unknown or invalid stop/resume requests return 404/409 without changing the run")
    void refusesInvalidTransitions() throws Exception {
        for (String operation : List.of("stop", "resume")) {
            mvc.perform(post("/v1/runs/" + UUID.randomUUID() + "/" + operation))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        }
        for (RunStatus state : RunStatus.values()) {
            UUID id = newRun();
            // Set each starting status directly; this test concerns only the HTTP transition contract.
            jdbc.update("update runs set status = ? where id = ?", state.name(), id);
            String operation = state == RunStatus.STOPPED || state == RunStatus.SUCCEEDED || state == RunStatus.FAILED
                    ? "stop" : "resume";
            Long version = runs.getRun(id).getVersion();
            mvc.perform(post("/v1/runs/" + id + "/" + operation)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
            assertThat(runs.getRun(id).getStatus()).isEqualTo(state);
            assertThat(runs.getRun(id).getVersion()).isEqualTo(version);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("AC-AGT-43.1 a stop accepted at the final boundary prevents a later success or failure transition")
    void acceptedStopCannotBecomeSuccessOrFailure(boolean succeeding) {
        UUID id = newRun();
        runs.claimNextRun();
        stops.stop(id);

        assertThatThrownBy(() -> {
            if (succeeding) {
                runs.succeedRun(id);
            } else {
                runs.failRun(id);
            }
        }).isInstanceOf(RunStoppedException.class);
        runs.stopRun(id);

        assertThat(runs.getRun(id).getStatus()).isEqualTo(RunStatus.STOPPED);
    }

    @Test
    @DisplayName("AC-AGT-43.6 metrics count stopped runs separately and exclude them from the success rate; "
            + "OpenAPI exposes stop, resume and STOPPED")
    void reportsStoppedRunsAndDocumentsTheOperations() throws Exception {
        UUID id = newRun();
        mvc.perform(post("/v1/runs/" + id + "/stop")).andExpect(status().isAccepted());
        for (RunStatus state : List.of(RunStatus.SUCCEEDED, RunStatus.FAILED)) {
            UUID terminal = newRun();
            jdbc.update("update runs set status = ? where id = ?", state.name(), terminal);
        }

        mvc.perform(get("/v1/metrics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.byStatus.STOPPED").value(1))
                .andExpect(jsonPath("$.runs.successRate").value(0.5));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/v1/runs/{id}/stop'].post.responses['202']").exists())
                .andExpect(jsonPath("$.paths['/v1/runs/{id}/resume'].post.responses['202']").exists())
                .andExpect(jsonPath("$.components.schemas.RunResponse.properties.status.enum", hasItem("STOPPED")));
    }

    private UUID newRun() {
        UUID id = runs.createRun("Build a calculator").getId();
        created.add(id);
        return id;
    }

    private void approve(UUID id) throws Exception {
        mvc.perform(post("/v1/runs/" + id + "/decisions").contentType("application/json")
                .content("{\"decision\":\"APPROVE\",\"approver\":\"reviewer\"}"))
                .andExpect(status().isAccepted());
    }
}
