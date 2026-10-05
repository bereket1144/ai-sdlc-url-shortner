package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import io.github.bereket1144.agentcontroller.AgentControllerApplication;
import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.model.ModelClient;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * AGT-15 on the full application and the real PostgreSQL schema: the requirements, design and planning stages run on
 * their agents with a scripted model, and the stages after them stay on their dummy nodes. The runs are driven by
 * calling the scheduler by hand, so the test sees exactly what each request and each poll changed.
 */
@SpringBootTest(properties = {"run.dispatch.polling-enabled=false", "agent.model-client.mode=scripted",
        "agent.stages.requirements=agent", "agent.stages.design=agent", "agent.stages.planning=agent",
        "agent.approval.required-for=design,planning"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ApprovalFullStackTest.Scripted.class})
class ApprovalFullStackTest {

    private static final String NO_QUESTIONS = "{\"questions\":[],\"assumptions\":[]}";
    private static final Map<String, String> ANSWERS = Map.of(
            "requirements", "{\"stories\":[{\"id\":\"S-1\",\"title\":\"Shorten\",\"role\":\"user\",\"want\":\"w\","
                    + "\"benefit\":\"b\",\"acceptanceCriteria\":[{\"id\":\"S-1.1\",\"text\":\"t\"}]}],"
                    + "\"openQuestions\":[],\"assumptions\":[]}",
            "design", "{\"components\":[\"c\"],\"interfaces\":[\"i\"],\"dataFlow\":\"d\",\"decisions\":[\"x\"],"
                    + "\"alternatives\":[\"y\"],\"risks\":[\"r\"],\"diagrams\":[\"graph TD\"]}",
            "planning", "{\"tasks\":[\"t1\"]}");
    /** The user text of every model call, in the order of the calls, with the role that was called. */
    private static final ConcurrentLinkedQueue<Prompt> PROMPTS = new ConcurrentLinkedQueue<>();

    private record Prompt(String role, String text) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Scripted {
        @Bean
        ModelClient scriptedModel() {
            return (role, profile, systemText, userText) -> {
                PROMPTS.add(new Prompt(role, userText));
                String text = role.equals("requirements") && userText.contains("CLARIFICATION phase")
                        ? NO_QUESTIONS : ANSWERS.get(role);
                if (text == null) {
                    throw new IllegalStateException("Unexpected model call: " + role);
                }
                return new ModelResult(text, FinishReason.COMPLETE, null, null, Duration.ZERO);
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunService runs;

    @Autowired
    private RunDispatcher dispatcher;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgreSQLContainer database;

    private final List<UUID> created = new ArrayList<>();
    private UUID id;

    // The rows of the runs this test made, children first so that the foreign key to runs holds at every delete.
    @AfterEach
    void deleteWhatTheTestCreated() {
        for (UUID run : created) {
            for (String table : List.of("approval_decision", "clarification_batch", "run_stage", "artifact",
                    "model_call", "model_budget_reservation")) {
                jdbc.update("delete from " + table + " where run_id = ?", run);
            }
            jdbc.update("delete from runs where id = ?", run);
        }
        PROMPTS.clear();
    }

    @Test
    @DisplayName("AC-AGT-15.1, 15.2 the run stops after the design is stored and waits (WAITING_FOR_INPUT): the read "
            + "response shows the approval of that version, no other wait is shown, no model call is made and the "
            + "scheduler does not claim the run; the response shows no approval before the wait")
    void theRunWaitsAfterTheDesign() throws Exception {
        id = newRun();
        read().andExpect(jsonPath("$.status").value("CREATED")).andExpect(jsonPath("$.approval").doesNotExist());

        poll();

        var design = artifacts.find(id, "design", 1).orElseThrow();
        read().andExpect(jsonPath("$.status").value("WAITING_FOR_INPUT"))
                .andExpect(jsonPath("$.approval.kind").value("design"))
                .andExpect(jsonPath("$.approval.version").value(1))
                .andExpect(jsonPath("$.approval.fingerprint").value(design.fingerprint()))
                .andExpect(jsonPath("$.approval.summary").value(design.summary()))
                .andExpect(jsonPath("$.approval.rejectionsLeft").value(3))
                .andExpect(jsonPath("$.awaiting").doesNotExist())
                .andExpect(jsonPath("$.stages[2].state").value("FINISHED"))
                .andExpect(jsonPath("$.stages[3].state").value("NOT_STARTED"));
        assertThat(callsByRole()).isEqualTo(Map.of("requirements", 2L, "design", 1L));

        // A waiting run is not claimed: no stage runs and no model is called.
        assertThat(dispatcher.dispatchNext()).isFalse();
        poll();
        assertThat(callsByRole()).isEqualTo(Map.of("requirements", 2L, "design", 1L));
        read().andExpect(jsonPath("$.status").value("WAITING_FOR_INPUT"));
    }

    @Test
    @DisplayName("AC-AGT-15.1, 15.2, 15.4 an approval queues the run at the next stage, which waits in turn after the "
            + "plan is stored; the second approval lets the run finish SUCCEEDED, with no approval shown and no earlier "
            + "stage called again")
    void approvingTheDesignAndThePlanFinishesTheRun() throws Exception {
        id = newRun();
        poll();

        decide("APPROVE", "reviewer", null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(id.toString()))
                .andExpect(jsonPath("$.message").isNotEmpty());
        read().andExpect(jsonPath("$.status").value("CREATED")).andExpect(jsonPath("$.resumedFrom").value("planning"))
                .andExpect(jsonPath("$.attempt").value(1)).andExpect(jsonPath("$.approval").doesNotExist());
        poll();

        var plan = artifacts.find(id, "plan", 1).orElseThrow();
        read().andExpect(jsonPath("$.status").value("WAITING_FOR_INPUT"))
                .andExpect(jsonPath("$.approval.kind").value("plan"))
                .andExpect(jsonPath("$.approval.version").value(1))
                .andExpect(jsonPath("$.approval.fingerprint").value(plan.fingerprint()))
                .andExpect(jsonPath("$.approval.rejectionsLeft").value(3));
        assertThat(callsByRole()).isEqualTo(Map.of("requirements", 2L, "design", 1L, "planning", 1L));

        decide("APPROVE", "reviewer", "Looks right").andExpect(status().isAccepted());
        poll();

        read().andExpect(jsonPath("$.status").value("SUCCEEDED")).andExpect(jsonPath("$.approval").doesNotExist())
                .andExpect(jsonPath("$.resumedFrom").value("development"));
        // Eight stages in graph order; impact analysis does not run for a greenfield run.
        assertThat(stageStates()).containsExactly("FINISHED", "NOT_STARTED", "FINISHED", "FINISHED", "FINISHED",
                "FINISHED", "FINISHED", "FINISHED");
        // The design was approved once and not made again, and neither were the requirements.
        assertThat(callsByRole()).isEqualTo(Map.of("requirements", 2L, "design", 1L, "planning", 1L));
    }

    @Test
    @DisplayName("AC-AGT-15.3 an invalid body (no decision or an unknown one, a blank or too long approver, a comment over "
            + "5,000 characters, a rejection without a comment) is a 400 in the problem format, and nothing changes")
    void anInvalidBodyIsRefused() throws Exception {
        id = newRun();
        poll();
        Long version = runs.getRun(id).getVersion();

        for (String body : List.of("{}", "{\"decision\":\"MAYBE\",\"approver\":\"a\"}", "{\"decision\":\"APPROVE\"}",
                "{\"decision\":\"APPROVE\",\"approver\":\"  \"}",
                "{\"decision\":\"APPROVE\",\"approver\":\"" + "a".repeat(101) + "\"}",
                "{\"decision\":\"APPROVE\",\"approver\":\"a\",\"comment\":\"" + "c".repeat(5001) + "\"}",
                "{\"decision\":\"REJECT\",\"approver\":\"a\"}",
                "{\"decision\":\"REJECT\",\"approver\":\"a\",\"comment\":\" \"}")) {
            expectProblem(mvc.perform(post("/v1/runs/" + id + "/decisions").contentType("application/json")
                    .content(body)), 400);
        }

        assertUnchanged(id, RunStatus.WAITING_FOR_INPUT, version, 0);
    }

    @Test
    @DisplayName("AC-AGT-15.3 an unknown run is 404, a run that does not wait for an approval is 409, and a second "
            + "decision for the same version is 409, all in the problem format; nothing changes")
    void aDecisionThatCannotBeTakenIsRefused() throws Exception {
        expectProblem(decideOn(UUID.randomUUID(), "APPROVE", "reviewer", null), 404);

        id = newRun();
        poll();
        // Made after the poll, so that nothing claims it: a run that has not started waits for nothing.
        UUID notWaiting = newRun();
        Long version = runs.getRun(notWaiting).getVersion();
        expectProblem(decideOn(notWaiting, "APPROVE", "reviewer", null), 409);
        assertUnchanged(notWaiting, RunStatus.CREATED, version, 0);

        decide("APPROVE", "reviewer", null).andExpect(status().isAccepted());
        Long decidedVersion = runs.getRun(id).getVersion();
        expectProblem(decide("REJECT", "someone-else", "Changed my mind"), 409);
        assertUnchanged(id, RunStatus.CREATED, decidedVersion, 1);
    }

    @Test
    @DisplayName("AC-AGT-15.5 a rejection runs the same stage again with the reviewer's comment and all earlier ones in the "
            + "task, the next version waits for approval again, and the third rejection ends the run FAILED with "
            + "REJECTED; no earlier stage is called again and no comment is logged")
    void rejectingTheDesignRunsItAgainUntilTheLimit() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        try {
            id = newRun();
            poll();

            decide("REJECT", "PRIVATE_NAME", "PRIVATE_FIRST add a threat model").andExpect(status().isAccepted());
            read().andExpect(jsonPath("$.status").value("CREATED")).andExpect(jsonPath("$.resumedFrom").value("design"))
                    .andExpect(jsonPath("$.stages[2].state").value("NOT_STARTED"));
            poll();
            read().andExpect(jsonPath("$.status").value("WAITING_FOR_INPUT"))
                    .andExpect(jsonPath("$.approval.kind").value("design"))
                    .andExpect(jsonPath("$.approval.version").value(2))
                    .andExpect(jsonPath("$.approval.rejectionsLeft").value(2));

            decide("REJECT", "PRIVATE_NAME", "PRIVATE_SECOND name the alternatives").andExpect(status().isAccepted());
            poll();
            read().andExpect(jsonPath("$.approval.version").value(3))
                    .andExpect(jsonPath("$.approval.rejectionsLeft").value(1));

            decide("REJECT", "PRIVATE_NAME", "PRIVATE_THIRD still too thin").andExpect(status().isAccepted());
            poll();
        } finally {
            root.detachAppender(logged);
            logged.stop();
        }

        read().andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.failureReason.code").value("REJECTED"))
                .andExpect(jsonPath("$.failureReason.message").value(RunFailureReason.REJECTED.message()))
                .andExpect(jsonPath("$.approval").doesNotExist());
        // The first design call had no comment; the second had the first; the third had the first and the second.
        List<String> designPrompts = PROMPTS.stream().filter(prompt -> prompt.role().equals("design"))
                .map(Prompt::text).toList();
        assertThat(designPrompts).hasSize(3);
        assertThat(designPrompts.get(0)).doesNotContain("PRIVATE_");
        assertThat(designPrompts.get(1)).contains("PRIVATE_FIRST add a threat model").doesNotContain("PRIVATE_SECOND");
        assertThat(designPrompts.get(2)).contains("PRIVATE_FIRST add a threat model", "PRIVATE_SECOND name the alternatives")
                .doesNotContain("PRIVATE_THIRD");
        assertThat(designPrompts.get(2).indexOf("PRIVATE_FIRST")).isLessThan(designPrompts.get(2).indexOf("PRIVATE_SECOND"));
        // After the last rejection nothing runs, and the requirements were never made again.
        assertThat(callsByRole()).isEqualTo(Map.of("requirements", 2L, "design", 3L));
        assertThat(dispatcher.dispatchNext()).isFalse();
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("PRIVATE_"));
    }

    @Test
    @DisplayName("AC-AGT-15.6 every decision is stored with the approver, the decision, the comment, the artifact kind, "
            + "version and fingerprint, and the time")
    void everyDecisionIsStored() throws Exception {
        Instant before = Instant.now().minusSeconds(5);
        id = newRun();
        poll();
        decide("REJECT", "reviewer-1", "Add a threat model").andExpect(status().isAccepted());
        poll();
        decide("APPROVE", "reviewer-2", null).andExpect(status().isAccepted());

        List<Map<String, Object>> rows = jdbc.queryForList("select * from approval_decision where run_id = ? order by id", id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("stage", "design").containsEntry("artifact_kind", "design")
                .containsEntry("artifact_version", 1).containsEntry("decision", "REJECT")
                .containsEntry("approver", "reviewer-1").containsEntry("comment", "Add a threat model")
                .containsEntry("fingerprint", artifacts.find(id, "design", 1).orElseThrow().fingerprint());
        assertThat(rows.get(1)).containsEntry("artifact_version", 2).containsEntry("decision", "APPROVE")
                .containsEntry("approver", "reviewer-2").containsEntry("comment", null)
                .containsEntry("fingerprint", artifacts.find(id, "design", 2).orElseThrow().fingerprint());
        assertThat(jdbc.queryForList("select decided_at from approval_decision where run_id = ?",
                java.sql.Timestamp.class, id)).allSatisfy(time ->
                assertThat(time.toInstant()).isBetween(before, Instant.now().plusSeconds(5)));
    }

    @Test
    @DisplayName("AC-AGT-15.6 a fresh application instance on the same database still shows the wait with the same "
            + "request, and does not claim the run")
    void theWaitSurvivesARestart() throws Exception {
        id = newRun();
        poll();
        decide("APPROVE", "reviewer", null).andExpect(status().isAccepted());
        poll();
        var request = approvals.pending(id);
        assertThat(request.kind()).isEqualTo("plan");

        try (var restarted = new SpringApplicationBuilder(AgentControllerApplication.class, Scripted.class)
                .web(WebApplicationType.NONE).run(
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + database.getUsername(),
                        "--spring.datasource.password=" + database.getPassword(),
                        "--run.dispatch.polling-enabled=false", "--agent.model-client.mode=scripted",
                        "--agent.stages.requirements=agent", "--agent.stages.design=agent",
                        "--agent.stages.planning=agent", "--agent.approval.required-for=design,planning")) {
            RunResponse response = restarted.getBean(RunController.class).getRun(id);

            assertThat(response.status()).isEqualTo(RunStatus.WAITING_FOR_INPUT);
            assertThat(response.approval()).isEqualTo(request);
            assertThat(restarted.getBean(RunDispatcher.class).dispatchNext()).isFalse();
            assertThat(callsByRole()).isEqualTo(Map.of("requirements", 2L, "design", 1L, "planning", 1L));
        }
    }

    private UUID newRun() {
        UUID run = runs.createRun("Build a link shortener.").getId();
        created.add(run);
        return run;
    }

    /** Claims every queued run, as the scheduler does: the call returns when no run is left to claim. */
    private void poll() {
        new RunScheduler(dispatcher).poll();
    }

    private ResultActions read() throws Exception {
        return mvc.perform(get("/v1/runs/" + id));
    }

    private List<String> stageStates() throws Exception {
        return JsonPath.read(read().andReturn().getResponse().getContentAsString(), "$.stages[*].state");
    }

    private ResultActions decide(String decision, String approver, String comment) throws Exception {
        return decideOn(id, decision, approver, comment);
    }

    private ResultActions decideOn(UUID run, String decision, String approver, String comment) throws Exception {
        var body = JsonMapper.shared().createObjectNode().put("decision", decision).put("approver", approver);
        if (comment != null) {
            body.put("comment", comment);
        }
        return mvc.perform(post("/v1/runs/" + run + "/decisions").contentType("application/json")
                .content(body.toString()));
    }

    private static Map<String, Long> callsByRole() {
        return PROMPTS.stream().collect(Collectors.groupingBy(Prompt::role, Collectors.counting()));
    }

    private void assertUnchanged(UUID run, RunStatus status, Long version, int decisions) {
        Run stored = runs.getRun(run);
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getVersion()).isEqualTo(version);
        assertThat(jdbc.queryForObject("select count(*) from approval_decision where run_id = ?", Integer.class, run))
                .isEqualTo(decisions);
    }

    private static void expectProblem(ResultActions result, int status) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + status))
                .andExpect(jsonPath("$.status").value(status));
    }
}
