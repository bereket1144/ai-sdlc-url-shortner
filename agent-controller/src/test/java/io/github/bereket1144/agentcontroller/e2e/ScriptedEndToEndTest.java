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
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.model.ModelClient;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
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
 * are real git; only the model and the container are stand-ins.
 */
@SpringBootTest(properties = {
        "run.dispatch.polling-enabled=true", "run.dispatch.poll-delay-ms=200",
        "agent.model-client.mode=scripted",
        "agent.stages.requirements=agent", "agent.stages.design=agent", "agent.stages.planning=agent",
        "agent.stages.development=agent", "agent.stages.qa=agent", "agent.stages.review=agent",
        "agent.stages.documentation=agent"})
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
                String text = role.equals("requirements") && userText.contains("CLARIFICATION phase")
                        ? "{\"questions\":[],\"assumptions\":[]}" : ANSWERS.get(role);
                return new ModelResult(text, FinishReason.COMPLETE, null, null, Duration.ZERO);
            };
        }

        @Bean
        @Primary
        VerificationRunner fakeContainer() {
            return (workspace, commitId) -> {
                boolean filesThere = Files.exists(workspace.resolve("app.py"));
                Outcome outcome = filesThere ? BUILD_OUTCOME.get() : Outcome.NO_RESULT;
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

    @AfterEach
    void deleteWhatTheRunLeft() {
        for (String table : List.of("verification_result", "run_stage", "artifact", "model_call",
                "model_budget_reservation", "runs")) {
            jdbc.update("delete from " + table);
        }
        ROLES_CALLED.clear();
        BUILD_OUTCOME.set(Outcome.PASSED);
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
    @DisplayName("AC-AGT-62.15, AC-AGT-21.5 a failing build ends the run FAILED with VERIFICATION_FAILED and no document is written")
    void aFailingBuildEndsFailed() throws Exception {
        BUILD_OUTCOME.set(Outcome.FAILED);
        UUID id = postRequirement();

        awaitEnd(id);

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason.code").value("VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.verification.outcome").value("FAILED"));
        // The stages after development never ran.
        assertThat(ROLES_CALLED).containsExactlyInAnyOrder("requirements", "requirements", "design", "planning", "development");
        // The repository holds the development commit and nothing else: no README and no document.
        assertThat(git(BASE.resolve(id.toString()), "ls-tree", "-r", "--name-only", "main"))
                .isEqualTo("app.py\ntests/test_app.py");
    }

    private UUID postRequirement() throws Exception {
        String body = mvc.perform(post("/v1/runs").contentType("application/json")
                        .content("{\"requirement\":\"Build a link shortener.\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void awaitEnd(UUID id) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            String body = mvc.perform(get("/v1/runs/" + id)).andReturn().getResponse().getContentAsString();
            assertThat((String) JsonPath.read(body, "$.status")).isIn("SUCCEEDED", "FAILED");
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
