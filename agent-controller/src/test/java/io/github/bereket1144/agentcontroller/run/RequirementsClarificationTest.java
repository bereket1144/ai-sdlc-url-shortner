package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** One database-wired flow suite; only the model is scripted, and downstream stages stay deterministic. */
@SpringBootTest(properties = {"run.dispatch.polling-enabled=false", "agent.model-client.mode=scripted",
        "agent.stages.requirements=agent"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RequirementsClarificationTest.Scripted.class})
class RequirementsClarificationTest {
    private static final String EMPTY = "{\"questions\":[],\"assumptions\":[]}";
    private static final String BATCH = """
            {"stories":[],"openQuestions":[],"questions":[{"id":"model-id","text":"PRIVATE_Q duplicate handling?","why":"API contract",
            "proposedDefault":"New code"}],"assumptions":[]}
            """;
    private static final String STORIES = """
            {"stories":[{"id":"S-1","title":"Create","role":"user","want":"a code","benefit":"sharing",
            "acceptanceCriteria":[{"id":"S-1.1","text":"A URL receives a code"}]}],
            "openQuestions":[],"assumptions":["Runs as a single process"]}
            """;
    private static final String REPLY = """
            {"answers":[{"questionId":"Q-1","answer":"PRIVATE_REPLY reuse the code"}]}
            """;
    private static final ConcurrentLinkedQueue<String> ANSWERS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> PROMPTS = new ConcurrentLinkedQueue<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Scripted {
        @Bean
        ModelClient scriptedModel() {
            return (role, profile, systemText, userText) -> {
                PROMPTS.add(userText);
                String answer = ANSWERS.poll();
                if (answer == null) {
                    throw new IllegalStateException("Unexpected model call");
                }
                return new ModelResult(answer, FinishReason.COMPLETE, null, null, Duration.ZERO);
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RunService runs;
    @Autowired RunDispatcher dispatcher;
    @Autowired ClarificationService clarification;
    @Autowired ArtifactStore artifacts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PostgreSQLContainer database;
    private UUID id;

    @AfterEach
    void cleanUp() {
        if (id != null) {
            for (String table : List.of("clarification_batch", "run_stage", "artifact", "model_call",
                    "model_budget_reservation", "runs")) {
                jdbc.update("delete from " + table + " where " + (table.equals("runs") ? "id" : "run_id") + " = ?", id);
            }
        }
        ANSWERS.clear();
        PROMPTS.clear();
    }

    @Test
    @DisplayName("AC-AGT-64.1 at most four questions are stored, with no assumptions; empty story fields are ignored")
    void storesClarificationBeforeStories() {
        id = runs.createRun("PRIVATE_REQUIREMENT").getId();
        var oversized = JsonMapper.shared().readTree(BATCH).deepCopy();
        var questions = (tools.jackson.databind.node.ArrayNode) oversized.get("questions");
        for (int number = 2; number <= 5; number++) {
            questions.addObject().put("id", "model-" + number).put("text", "Priority " + number)
                    .put("why", "Product choice");
        }
        ANSWERS.add(oversized.toString());
        assertThat(dispatcher.dispatchNext()).isTrue();
        var batch = artifacts.find(id, "requirements", 1).orElseThrow().content();
        assertThat(batch.has("stories")).isFalse();
        assertThat(batch.has("openQuestions")).isFalse();
        assertThat(batch.get("questions")).hasSize(4);
        assertThat(batch.get("questions").get(0).get("id").asString()).isEqualTo("Q-1");
        assertThat(batch.get("questions").get(3).get("text").asString()).isEqualTo("Priority 4");
        assertThat(batch.has("assumptions")).isFalse();
        assertThat(clarification.questions(id).questions()).extracting(ClarificationService.Item::proposedDefault)
                .containsExactly("New code", null, null, null);
        assertThat(PROMPTS).singleElement().satisfies(prompt ->
                assertThat(prompt).contains("Python standard library", "SQLite", "pytest", "CLARIFICATION phase",
                        "at most four", "most to least important"));
    }

    @Test
    @DisplayName("AC-AGT-64.2 a waiting run releases dispatch, makes no further calls and starts no downstream stage")
    void waitingDoesNotDispatch() {
        waitingRun();
        assertThat(runs.getRun(id).getStatus()).isEqualTo(RunStatus.WAITING_FOR_INPUT);
        assertThat(dispatcher.dispatchNext()).isFalse();
        assertThat(PROMPTS).hasSize(1);
        assertThat(jdbc.queryForList("select stage from run_stage where run_id = ?", String.class, id))
                .containsExactly("requirements");
    }

    @Test
    @DisplayName("AC-AGT-64.3 the read API returns empty, open and answered questions, their versions, and 404 for unknown runs")
    void readsQuestions() throws Exception {
        id = runs.createRun("PRIVATE_REQUIREMENT").getId();
        mvc.perform(get("/v1/runs/" + id + "/questions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.questions").isEmpty());
        ANSWERS.add(BATCH);
        dispatcher.dispatchNext();
        mvc.perform(get("/v1/runs/" + id + "/questions"))
                .andExpect(jsonPath("$.questions[0].status").value("OPEN"))
                .andExpect(jsonPath("$.questions[0].artifactVersion").value(1))
                .andExpect(jsonPath("$.assumptions").doesNotExist());
        mvc.perform(get("/v1/runs/" + id)).andExpect(jsonPath("$.status").value("WAITING_FOR_INPUT"))
                .andExpect(jsonPath("$.awaiting.reason").value("requirementsClarification"))
                .andExpect(jsonPath("$.awaiting.artifactVersion").value(1));
        reply();
        mvc.perform(get("/v1/runs/" + id + "/questions"))
                .andExpect(jsonPath("$.questions[0].status").value("ANSWERED"))
                .andExpect(jsonPath("$.questions[0].answer").value("PRIVATE_REPLY reuse the code"));
        mvc.perform(get("/v1/runs/" + UUID.randomUUID() + "/questions")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-AGT-64.4 a missing, duplicate, unknown, blank or oversized answer is refused and changes nothing")
    void refusesInvalidBatch() throws Exception {
        waitingRun();
        for (String body : List.of("{\"answers\":[]}",
                "{\"answers\":[{\"questionId\":\"Q-1\",\"answer\":\"yes\"},{\"questionId\":\"Q-1\",\"answer\":\"yes\"}]}",
                "{\"answers\":[{\"questionId\":\"Q-1\",\"answer\":\"yes\"},{\"questionId\":\"Q-9\",\"answer\":\"yes\"}]}",
                REPLY.replace("PRIVATE_REPLY reuse the code", " "),
                REPLY.replace("PRIVATE_REPLY reuse the code", "x".repeat(5001)))) {
            mvc.perform(post("/v1/runs/" + id + "/replies").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        assertThat(runs.getRun(id).getStatus()).isEqualTo(RunStatus.WAITING_FOR_INPUT);
        assertThat(clarification.questions(id).questions()).allMatch(item -> item.status().equals("OPEN"));
    }

    @Test
    @DisplayName("AC-AGT-64.5 an accepted reply returns 202 and queues the run; a repeated reply returns 409")
    void acceptsOneCompleteReply() throws Exception {
        waitingRun();
        mvc.perform(post("/v1/runs/" + id + "/replies").contentType("application/json").content(REPLY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(id.toString()))
                .andExpect(jsonPath("$.message").value("Answers submitted."));
        assertThat(runs.getRun(id).getStatus()).isEqualTo(RunStatus.CREATED);
        assertThat(clarification.questions(id).questions()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("ANSWERED");
            assertThat(item.answeredAt()).isNotNull();
        });
        mvc.perform(post("/v1/runs/" + id + "/replies").contentType("application/json").content(REPLY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("AC-AGT-64.6 after the reply the run goes straight to the stories, with no extra artifact version")
    void continuesStraightToTheStories() throws Exception {
        waitingRun();
        reply();
        ANSWERS.add(STORIES);
        new RunScheduler(dispatcher).poll();
        assertThat(runs.getRun(id).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(PROMPTS).hasSize(2);
        assertThat(PROMPTS.stream().toList().getLast()).contains("PRIVATE_REQUIREMENT", "PRIVATE_REPLY");
        assertThat(jdbc.queryForObject("select count(*) from model_call where run_id = ?", Integer.class, id)).isEqualTo(2);
        var stories = artifacts.find(id, "requirements", 2).orElseThrow();
        assertThat(stories.content().get("stories")).hasSize(1);
        assertThat(stories.content().get("assumptions")).singleElement()
                .satisfies(assumption -> assertThat(assumption.asString()).isEqualTo("Runs as a single process"));
        assertThat(artifacts.find(id, "requirements", 3)).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-64.7 with no question the run does not pause and stores only the stories, with the agent's assumptions")
    void clearRequirementRunsThrough() {
        id = runs.createRun("A clear requirement").getId();
        ANSWERS.add(EMPTY);
        ANSWERS.add(STORIES);
        new RunScheduler(dispatcher).poll();
        assertThat(runs.getRun(id).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        var finalContent = artifacts.find(id, "requirements", 1).orElseThrow().content();
        assertThat(finalContent.get("stories")).hasSize(1);
        assertThat(finalContent.get("assumptions").get(0).asString()).isEqualTo("Runs as a single process");
        assertThat(artifacts.find(id, "requirements", 2)).isEmpty();
        assertThat(PROMPTS).hasSize(2);
    }

    @Test
    @DisplayName("AC-AGT-64.8 a fresh application instance reads the persisted wait and continues after the reply")
    void resumesInAFreshApplicationInstance() {
        waitingRun();
        // A second application owns new services and persistence sessions, using the same database.
        try (var restarted = new SpringApplicationBuilder(AgentControllerApplication.class, Scripted.class)
                .web(WebApplicationType.NONE).run(
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + database.getUsername(),
                        "--spring.datasource.password=" + database.getPassword(),
                        "--run.dispatch.polling-enabled=false", "--agent.model-client.mode=scripted",
                        "--agent.stages.requirements=agent")) {
            var saved = restarted.getBean(ClarificationService.class);
            assertThat(saved.questions(id)).isEqualTo(clarification.questions(id));
            var restartedDispatcher = restarted.getBean(RunDispatcher.class);
            assertThat(restartedDispatcher.dispatchNext()).isFalse();
            assertThat(PROMPTS).hasSize(1);
            saved.reply(id, JsonMapper.shared().readValue(REPLY, ClarificationService.Replies.class));
            ANSWERS.add(STORIES);
            new RunScheduler(restartedDispatcher).poll();
            assertThat(runs.getRun(id).getStatus()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(PROMPTS).hasSize(2);
        }
    }

    @Test
    @DisplayName("AC-AGT-64.9 the API documents batch replies and waiting; logs do not contain clarification text")
    void documentsTheContractWithoutLoggingText() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try {
            waitingRun();
            reply();
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
        assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("PRIVATE_"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/v1/runs/{id}/questions'].get").exists())
                .andExpect(jsonPath("$.paths['/v1/runs/{id}/replies'].post.responses['202']").exists())
                .andExpect(jsonPath("$.components.schemas.Replies.properties.answers.type").value("array"));
    }

    private void waitingRun() {
        id = runs.createRun("PRIVATE_REQUIREMENT").getId();
        ANSWERS.add(BATCH);
        assertThat(dispatcher.dispatchNext()).isTrue();
    }

    private void reply() throws Exception {
        mvc.perform(post("/v1/runs/" + id + "/replies").contentType("application/json").content(REPLY))
                .andExpect(status().isAccepted());
    }
}
