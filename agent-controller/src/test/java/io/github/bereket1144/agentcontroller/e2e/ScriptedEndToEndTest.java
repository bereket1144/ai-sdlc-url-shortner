package io.github.bereket1144.agentcontroller.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.model.ModelClient;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import io.github.bereket1144.agentcontroller.run.Run;
import io.github.bereket1144.agentcontroller.run.RunRepository;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRunner;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC-AGT-62.15: a requirement posted through the HTTP API goes through every stage with a scripted model and a fake
 * container, and ends with stored artifacts, a commit and a stored verification result. The workspace and its commit
 * are real git; only the model and the container are stand-ins. AGT-66: a failed run is retried through the API.
 */
@SpringBootTest(properties = {
        "run.dispatch.polling-enabled=true", "run.dispatch.poll-delay-ms=200",
        "agent.model-client.mode=scripted",
        "agent.stages.requirements=agent", "agent.stages.impactAnalysis=agent", "agent.stages.design=agent",
        "agent.stages.planning=agent", "agent.stages.development=agent", "agent.stages.qa=agent",
        "agent.stages.review=agent", "agent.stages.documentation=agent"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ScriptedEndToEndTest.Stand_ins.class})
// The scheduler of this context must not outlive the class and claim another test's runs.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScriptedEndToEndTest {

    private static final Path BASE = temporaryFolder("workspaces");
    private static final AtomicReference<Outcome> BUILD_OUTCOME = new AtomicReference<>(Outcome.PASSED);
    private static final Map<String, String> ANSWERS = Map.of(
            "requirements", "{\"stories\":[{\"id\":\"S-1\",\"title\":\"Shorten\",\"role\":\"user\",\"want\":\"w\","
                    + "\"benefit\":\"b\",\"acceptanceCriteria\":[{\"id\":\"S-1.1\",\"text\":\"t\"}]}],"
                    + "\"openQuestions\":[],\"assumptions\":[]}",
            "impactAnalysis", "{\"affectedFiles\":[\"app.py\"],\"affectedBehaviors\":[\"b\"],\"dependencies\":[\"d\"],"
                    + "\"dataFlows\":[\"f\"],\"risks\":[\"r\"],\"evidence\":[\"e\"]}",
            "design", "{\"components\":[\"c\"],\"interfaces\":[\"i\"],\"dataFlow\":\"d\",\"decisions\":[\"x\"],"
                    + "\"alternatives\":[\"y\"],\"risks\":[\"r\"],\"diagrams\":[\"graph TD\"]}",
            "planning", "{\"tasks\":[\"t1\"]}",
            "development", "{\"changes\":[{\"path\":\"app.py\",\"content\":\"print(1)\\n\"},"
                    + "{\"path\":\"tests/test_app.py\",\"content\":\"def test_x():\\n    pass\\n\"}],"
                    + "\"rationale\":\"r\",\"affectedCriteria\":[\"S-1.1\"],\"errorHandling\":\"e\","
                    + "\"verificationNotes\":[\"n\"]}",
            "qa", "{\"proposedTests\":[\"t\"],\"criterionMapping\":{\"S-1.1\":\"test_x\"},"
                    + "\"observedEvidence\":[\"e\"],\"coverageGaps\":[]}",
            "review", "{\"reviewedFiles\":[\"app.py\"],\"findings\":[],\"evidence\":[\"e\"],"
                    + "\"resolutionStatus\":\"none\"}",
            "documentation", "{\"setup\":\"s\",\"usage\":\"u\",\"architecture\":\"a\",\"examples\":[\"e\"],"
                    + "\"knownLimits\":[\"l\"],\"evidenceLinks\":[\"l\"]}");
    private static final ConcurrentLinkedQueue<String> ROLES_CALLED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger DEVELOPMENT_CALLS = new AtomicInteger();
    /** The next this many builds fail; after that a build gives BUILD_OUTCOME. */
    private static final AtomicInteger BUILDS_TO_FAIL = new AtomicInteger();
    /** Whether the requirements agent asks one question the first time it is asked what to clarify. */
    private static final AtomicBoolean ASK_A_QUESTION = new AtomicBoolean();
    private static final AtomicInteger CLARIFICATION_CALLS = new AtomicInteger();
    private static final ConcurrentLinkedQueue<String> DEVELOPMENT_PROMPTS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> REQUIREMENTS_PROMPTS = new ConcurrentLinkedQueue<>();
    /** The user text of every model call, in the order of the calls, with the role that was called. */
    private static final ConcurrentLinkedQueue<Prompt> PROMPTS = new ConcurrentLinkedQueue<>();

    private record Prompt(String role, String text) { }

    @DynamicPropertySource
    static void workspace(DynamicPropertyRegistry registry) {
        registry.add("workspace.base", BASE::toString);
    }

    /** The model and the container are the only stand-ins; the primary bean wins over the Docker runner. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Stand_ins {

        @Bean
        ModelClient scriptedModel() {
            return (role, profile, systemText, userText) -> {
                ROLES_CALLED.add(role);
                PROMPTS.add(new Prompt(role, userText));
                String text = ANSWERS.get(role);
                if (role.equals("requirements")) {
                    REQUIREMENTS_PROMPTS.add(userText);
                }
                if (role.equals("requirements") && userText.contains("CLARIFICATION phase")) {
                    boolean ask = CLARIFICATION_CALLS.incrementAndGet() == 1 && ASK_A_QUESTION.get();
                    text = ask ? "{\"questions\":[{\"id\":\"x\",\"text\":\"Which codes?\",\"why\":\"w\"}],"
                            + "\"assumptions\":[]}" : "{\"questions\":[],\"assumptions\":[]}";
                } else if (role.equals("development")) {
                    DEVELOPMENT_PROMPTS.add(userText);
                    // Each attempt changes app.py, so a fix attempt has something new to commit.
                    text = text.replace("print(1)", "print(" + DEVELOPMENT_CALLS.incrementAndGet() + ")");
                }
                return new ModelResult(text, FinishReason.COMPLETE, null, null, Duration.ZERO);
            };
        }

        @Bean
        @Primary
        VerificationRunner fakeContainer() {
            return (workspace, commitId) -> {
                boolean filesThere = Files.exists(workspace.resolve("app.py"));
                Outcome outcome = Outcome.NO_RESULT;
                if (filesThere) {
                    boolean failing = BUILDS_TO_FAIL.getAndUpdate(left -> Math.max(0, left - 1)) > 0;
                    outcome = failing ? Outcome.FAILED : BUILD_OUTCOME.get();
                }
                return new VerificationResult(outcome, 1, outcome == Outcome.PASSED ? 1 : 0,
                        outcome == Outcome.FAILED ? 1 : 0, outcome == Outcome.FAILED ? List.of("test_x") : List.of(),
                        "", commitId);
            };
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RunRepository runs;

    @Autowired
    private VerificationRecords verifications;

    @AfterEach
    void deleteWhatTheRunLeft() {
        for (String table : List.of("verification_result", "run_stage", "artifact", "model_call",
                "model_budget_reservation")) {
            jdbc.update("delete from " + table);
        }
        // A run that changes another points at it, so those runs go first.
        jdbc.update("delete from runs where base_run_id is not null");
        jdbc.update("delete from runs");
        PROMPTS.clear();
        ROLES_CALLED.clear();
        DEVELOPMENT_CALLS.set(0);
        BUILD_OUTCOME.set(Outcome.PASSED);
        BUILDS_TO_FAIL.set(0);
        ASK_A_QUESTION.set(false);
        CLARIFICATION_CALLS.set(0);
        DEVELOPMENT_PROMPTS.clear();
        REQUIREMENTS_PROMPTS.clear();
    }

    @Test
    @DisplayName("AC-AGT-62.15, AC-AGT-21.1, AC-AGT-21.3 a posted requirement goes through every stage and ends SUCCEEDED with artifacts, a commit, a passing result and the documents in a second commit by the controller")
    void aRequirementEndsSucceeded() throws Exception {
        UUID id = postRequirement();

        awaitEnd(id);

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.artifacts.length()").value(7))
                .andExpect(jsonPath("$.verification.outcome").value("PASSED"))
                .andExpect(jsonPath("$.commit").isNotEmpty());
        List<String> states = JsonPath.read(mvc.perform(get("/v1/runs/" + id)).andReturn().getResponse()
                .getContentAsString(), "$.stages[*].state");
        // Eight stages in graph order; impact analysis does not run for a greenfield run.
        assertThat(states).containsExactly("FINISHED", "NOT_STARTED", "FINISHED", "FINISHED", "FINISHED",
                "FINISHED", "FINISHED", "FINISHED");
        mvc.perform(get("/v1/runs/" + id + "/artifacts/design/1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dataFlow").value("d"));
        // The commit is on main of the run's own new repository and holds the scripted files.
        Path workspace = BASE.resolve(id.toString());
        String commit = JsonPath.read(mvc.perform(get("/v1/runs/" + id)).andReturn().getResponse()
                .getContentAsString(), "$.commit");
        assertThat(git(workspace, "rev-parse", "main~1")).isEqualTo(commit);
        assertThat(git(workspace, "show", "--name-only", "--format=", commit))
                .contains("app.py", "tests/test_app.py");
        // The second commit, after the stages joined, holds the README and the design document, by the controller.
        assertThat(git(workspace, "rev-list", "--count", "main")).isEqualTo("2");
        assertThat(git(workspace, "show", "--name-only", "--format=", "main"))
                .contains("README.md", "docs/design.md");
        assertThat(git(workspace, "log", "-1", "--format=%an <%ae>", "main"))
                .isEqualTo("controller <controller@agent.invalid>");
        assertThat(ROLES_CALLED).containsExactlyInAnyOrder("requirements", "requirements", "design", "planning", "development", "qa",
                "review", "documentation");
    }

    @Test
    @DisplayName("AC-AGT-62.15, AC-AGT-21.5, AC-AGT-65.4, AC-AGT-65.5 a build that keeps failing is attempted twice more, then ends the run FAILED with VERIFICATION_FAILED and the development stage FAILED, and no document is written")
    void aFailingBuildEndsFailed() throws Exception {
        BUILD_OUTCOME.set(Outcome.FAILED);
        UUID id = postRequirement();

        awaitEnd(id);

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason.code").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.verification.outcome").value("FAILED"));
        List<String> states = JsonPath.read(mvc.perform(get("/v1/runs/" + id)).andReturn().getResponse()
                .getContentAsString(), "$.stages[*].state");
        assertThat(states).containsExactly("FINISHED", "NOT_STARTED", "FINISHED", "FINISHED", "FAILED",
                "NOT_STARTED", "NOT_STARTED", "NOT_STARTED");
        // Development ran three times, one attempt and two fix attempts; the stages after it never ran.
        assertThat(ROLES_CALLED).containsExactlyInAnyOrder("requirements", "requirements", "design", "planning",
                "development", "development", "development");
        // The repository holds the three development commits and nothing else: no README and no document.
        Path workspace = BASE.resolve(id.toString());
        assertThat(git(workspace, "rev-list", "--count", "main")).isEqualTo("3");
        assertThat(git(workspace, "ls-tree", "-r", "--name-only", "main")).isEqualTo("app.py\ntests/test_app.py");
    }

    @Test
    @DisplayName("AC-AGT-66.1, 66.2, 66.4 a failed run is retried through the API: it resumes at the stage that failed "
            + "without calling the model for the stages before it, the failure reason is cleared, and it ends SUCCEEDED")
    void aFailedRunIsResumedAtTheStageThatFailed() throws Exception {
        BUILDS_TO_FAIL.set(3);
        UUID id = postRequirement();
        awaitEnd(id);
        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.resumedFrom").doesNotExist());

        mvc.perform(post("/v1/runs/" + id + "/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.attempt").value(2));
        mvc.perform(get("/v1/runs/" + id)).andExpect(jsonPath("$.failureReason").doesNotExist());
        awaitEnd(id);

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.resumedFrom").value("development"))
                .andExpect(jsonPath("$.verification.outcome").value("PASSED"));
        // Requirements, design and planning ran once, in the first attempt. Development ran three times there and once
        // more in the retry, which found its first check failed and so was a fix attempt.
        assertThat(callsByRole()).containsExactlyInAnyOrderEntriesOf(Map.of("requirements", 2L, "design", 1L,
                "planning", 1L, "development", 4L, "qa", 1L, "review", 1L, "documentation", 1L));
        assertThat(DEVELOPMENT_PROMPTS).element(3).asString().contains("FIX attempt");
    }

    @Test
    @DisplayName("AC-AGT-66.3, 66.4, 66.5 when the resumed attempt fails too, the next attempt starts from the beginning "
            + "with the stored answers and writes its code afresh; the stage retries apply in every attempt and the "
            + "failure reason is set again each time")
    void aSecondRetryStartsFromTheBeginning() throws Exception {
        ASK_A_QUESTION.set(true);
        BUILDS_TO_FAIL.set(6);
        UUID id = postRequirement();
        awaitStatus(id, "WAITING_FOR_INPUT");
        mvc.perform(post("/v1/runs/" + id + "/replies").contentType("application/json")
                        .content("{\"answers\":[{\"questionId\":\"Q-1\",\"answer\":\"Use short codes\"}]}"))
                .andExpect(status().isAccepted());
        awaitEnd(id);

        mvc.perform(post("/v1/runs/" + id + "/retry")).andExpect(status().isAccepted());
        awaitEnd(id);
        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason.code").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.resumedFrom").value("development"));

        mvc.perform(post("/v1/runs/" + id + "/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.attempt").value(3));
        awaitEnd(id);

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.attempt").value(3))
                .andExpect(jsonPath("$.resumedFrom").value("START"));
        List<String> states = JsonPath.read(mvc.perform(get("/v1/runs/" + id)).andReturn().getResponse()
                .getContentAsString(), "$.stages[*].state");
        assertThat(states).containsExactly("FINISHED", "NOT_STARTED", "FINISHED", "FINISHED", "FINISHED",
                "FINISHED", "FINISHED", "FINISHED");
        // The question was asked once, in the first attempt, and the stories of the last attempt were written from its
        // answer. The stages before development ran again in the last attempt; development was attempted three times
        // in each of the first two attempts, and once in the last.
        assertThat(CLARIFICATION_CALLS).hasValue(1);
        assertThat(REQUIREMENTS_PROMPTS).last().asString().contains("Use short codes");
        assertThat(callsByRole()).containsExactlyInAnyOrderEntriesOf(Map.of("requirements", 3L, "design", 2L,
                "planning", 2L, "development", 7L, "qa", 1L, "review", 1L, "documentation", 1L));
        // Fix attempts: two in the first attempt, three in the resumed one. The restart writes its code afresh.
        assertThat(DEVELOPMENT_PROMPTS.stream().map(prompt -> prompt.contains("FIX attempt")).toList())
                .containsExactly(false, true, true, true, true, true, false);
    }

    @Test
    @DisplayName("AC-AGT-49.1, 49.2, 49.3, 49.4 a change run on a finished run ends SUCCEEDED: the impact stage ran and "
            + "the design and planning agents received its artifact, the four agents were shown the existing files before "
            + "their other inputs and no other agent was, the new repository holds the first run's commits and a new "
            + "one, and the first run's repository is unchanged")
    void aChangeRunStartsFromTheEarlierRun() throws Exception {
        UUID first = postRequirement();
        awaitEnd(first);
        mvc.perform(get("/v1/runs/" + first)).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        List<Prompt> firstRunPrompts = List.copyOf(PROMPTS);
        Path firstRepository = BASE.resolve(first.toString());
        List<String> firstCommits = git(firstRepository, "rev-list", "main").lines().toList();

        UUID second = postChange(first, "Add an expiry to links.");
        awaitEnd(second);

        String body = mvc.perform(get("/v1/runs/" + second)).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.kind").value("BROWNFIELD"))
                .andExpect(jsonPath("$.baseRunId").value(first.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(body, "$.stages[*].state")).containsOnly("FINISHED");
        // The first run was a greenfield run: no agent of it was shown files, and development was not told to change any.
        assertThat(firstRunPrompts).noneMatch(prompt -> prompt.text().contains("kind=SELECTED_WORKSPACE_FILES"));
        assertThat(firstRunPrompts).noneMatch(prompt -> prompt.text().contains("CHANGE attempt"));
        // The change run: impact analysis ran once, and the four agents read the base's app.py before the requirements.
        List<Prompt> changePrompts = PROMPTS.stream().skip(firstRunPrompts.size()).toList();
        for (String role : List.of("impactAnalysis", "design", "planning", "development")) {
            String text = onlyPromptOf(changePrompts, role);
            assertThat(text).contains("[FILE path=app.py]\nprint(1)\n");
            assertThat(text.indexOf("kind=SELECTED_WORKSPACE_FILES")).isLessThan(text.indexOf("kind=REQUIREMENTS_ARTIFACT"));
        }
        assertThat(changePrompts.stream().filter(prompt -> List.of("requirements", "qa", "review", "documentation")
                .contains(prompt.role()))).noneMatch(prompt -> prompt.text().contains("kind=SELECTED_WORKSPACE_FILES"));
        // The design and planning agents received the impact artifact, which the lineage records as consumed.
        assertThat(onlyPromptOf(changePrompts, "design")).contains("kind=IMPACT_ANALYSIS_ARTIFACT");
        assertThat(onlyPromptOf(changePrompts, "planning")).contains("kind=IMPACT_ANALYSIS_ARTIFACT");
        assertThat((List<String>) JsonPath.read(mvc.perform(get("/v1/runs/" + second + "/lineage")).andReturn()
                .getResponse().getContentAsString(), "$.artifacts[?(@.kind=='design')].builtFrom[*].kind"))
                .contains("impactAnalysis");
        // Development was told to change the files that are there, and rewrote app.py whole.
        assertThat(onlyPromptOf(changePrompts, "development")).contains("CHANGE attempt");
        // The second repository holds the first run's commits and the new commit of this run, which rewrote app.py.
        Path secondRepository = BASE.resolve(second.toString());
        List<String> secondCommits = git(secondRepository, "rev-list", "main").lines().toList();
        String commit = JsonPath.read(body, "$.commit");
        assertThat(secondCommits).containsAll(firstCommits).contains(commit).hasSizeGreaterThan(firstCommits.size());
        assertThat(firstCommits).doesNotContain(commit);
        assertThat(git(secondRepository, "show", "main:app.py")).isEqualTo("print(2)");
        // The first run's repository is as it was.
        assertThat(git(firstRepository, "rev-list", "main").lines().toList()).isEqualTo(firstCommits);
        assertThat(git(firstRepository, "show", "main:app.py")).isEqualTo("print(1)");
        assertThat(git(firstRepository, "status", "--porcelain")).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-50.5 a change run whose base run's workspace no longer exists ends FAILED with UNEXPECTED and "
            + "leaves no workspace of its own")
    void aChangeRunWithoutItsBaseWorkspaceFails() throws Exception {
        Run base = new Run("The earlier run");
        base.start();
        base.succeed();
        UUID baseId = runs.save(base).getId();
        verifications.record(baseId, new VerificationResult(Outcome.PASSED, 1, 1, 0, List.of(), "", "abc123"));

        UUID id = postChange(baseId, "Add an expiry to links.");
        awaitEnd(id);

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason.code").value("UNEXPECTED"));
        assertThat(BASE.resolve(id.toString())).doesNotExist();
    }

    private static String onlyPromptOf(List<Prompt> prompts, String role) {
        List<String> texts = prompts.stream().filter(prompt -> prompt.role().equals(role)).map(Prompt::text).toList();
        assertThat(texts).as("calls of " + role).hasSize(1);
        return texts.get(0);
    }

    private UUID postChange(UUID base, String requirement) throws Exception {
        String body = mvc.perform(post("/v1/runs").contentType("application/json")
                        .content("{\"requirement\":\"" + requirement + "\",\"kind\":\"BROWNFIELD\",\"baseRunId\":\""
                                + base + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static Map<String, Long> callsByRole() {
        return ROLES_CALLED.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private UUID postRequirement() throws Exception {
        String body = mvc.perform(post("/v1/runs").contentType("application/json")
                        .content("{\"requirement\":\"Build a link shortener.\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void awaitEnd(UUID id) {
        awaitStatus(id, "SUCCEEDED", "FAILED");
    }

    private void awaitStatus(UUID id, String... statuses) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            String body = mvc.perform(get("/v1/runs/" + id)).andReturn().getResponse().getContentAsString();
            assertThat((String) JsonPath.read(body, "$.status")).isIn((Object[]) statuses);
        });
    }

    private static Path temporaryFolder(String name) {
        try {
            return Files.createTempDirectory(name);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String git(Path folder, String... arguments) {
        try {
            List<String> command = new java.util.ArrayList<>(List.of("git"));
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git failed: " + output);
            }
            return output;
        } catch (IOException | InterruptedException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
