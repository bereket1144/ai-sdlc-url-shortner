package io.github.bereket1144.agentcontroller.run;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.NewArtifact;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

// AGT-47 on the full application and the real PostgreSQL schema: artifacts are stored through the real store.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RunLineageFullStackTest {

    private static final ModelProfile PROFILE = new ModelProfile("local", "qwen-test",
            URI.create("http://localhost:11434"), 32768, 4096, Duration.ofSeconds(300), 1, 2, 0.0, null);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunRepository runs;

    @Autowired
    private ArtifactStore artifacts;

    @Autowired
    private VerificationRecords verifications;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID created;

    // The rows of the run this test made, children first so that the foreign keys to runs hold at every delete.
    @AfterEach
    void deleteWhatTheTestCreated() {
        if (created == null) {
            return;
        }
        jdbc.update("delete from verification_result where run_id = ?", created);
        jdbc.update("delete from artifact where run_id = ?", created);
        jdbc.update("delete from runs where id = ?", created);
    }

    @Test
    @DisplayName("AC-AGT-47.1, AC-AGT-47.2 a run with no artifact has empty lists; stored artifacts show what each was built from, the commit, the outcome and each file with its criteria")
    void lineageShowsWhatWasBuiltFromWhat() throws Exception {
        created = runs.save(new Run("requirement")).getId();
        String path = "/v1/runs/" + created + "/lineage";

        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifacts", hasSize(0)))
                .andExpect(jsonPath("$.files", hasSize(0)))
                .andExpect(jsonPath("$.commit").doesNotExist())
                .andExpect(jsonPath("$.verificationOutcome").doesNotExist());

        ArtifactRef requirements = artifacts.store(new NewArtifact(created, "requirements",
                JsonMapper.shared().readTree("{\"stories\":[]}"), "one story", PROFILE, List.of()));
        artifacts.store(new NewArtifact(created, "development", JsonMapper.shared().readTree(
                "{\"changes\":[{\"path\":\"app.py\",\"content\":\"print(1)\"},"
                        + "{\"path\":\"tests/test_app.py\",\"content\":\"pass\"}],"
                        + "\"affectedCriteria\":[\"S-1.1\",\"S-1.2\"]}"),
                "two files", PROFILE, List.of(requirements)));
        verifications.record(created, new VerificationResult(Outcome.PASSED, 1, 1, 0, List.of(), "", "abc123"));

        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifacts", hasSize(2)))
                .andExpect(jsonPath("$.artifacts[0].kind").value("requirements"))
                .andExpect(jsonPath("$.artifacts[0].version").value(1))
                .andExpect(jsonPath("$.artifacts[0].fingerprint").value(requirements.fingerprint()))
                .andExpect(jsonPath("$.artifacts[0].summary").value("one story"))
                .andExpect(jsonPath("$.artifacts[0].builtFrom", hasSize(0)))
                .andExpect(jsonPath("$.artifacts[1].kind").value("development"))
                .andExpect(jsonPath("$.artifacts[1].builtFrom", hasSize(1)))
                .andExpect(jsonPath("$.artifacts[1].builtFrom[0].kind").value("requirements"))
                .andExpect(jsonPath("$.artifacts[1].builtFrom[0].version").value(1))
                .andExpect(jsonPath("$.artifacts[1].builtFrom[0].fingerprint").value(requirements.fingerprint()))
                .andExpect(jsonPath("$.commit").value("abc123"))
                .andExpect(jsonPath("$.verificationOutcome").value("PASSED"))
                .andExpect(jsonPath("$.files", hasSize(2)))
                .andExpect(jsonPath("$.files[0].path").value("app.py"))
                .andExpect(jsonPath("$.files[0].criteria[0]").value("S-1.1"))
                .andExpect(jsonPath("$.files[0].criteria[1]").value("S-1.2"))
                .andExpect(jsonPath("$.files[1].path").value("tests/test_app.py"))
                .andExpect(jsonPath("$.files[1].criteria", hasSize(2)));
    }

    @Test
    @DisplayName("AC-AGT-47.2 the lineage of an unknown run is a 404 in the problem format")
    void anUnknownRunIsNotFound() throws Exception {
        mvc.perform(get("/v1/runs/" + UUID.randomUUID() + "/lineage"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Run not found."));
    }
}
