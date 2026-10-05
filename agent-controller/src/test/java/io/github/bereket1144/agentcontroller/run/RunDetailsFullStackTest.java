package io.github.bereket1144.agentcontroller.run;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RunDetailsFullStackTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunRepository runs;

    @Autowired
    private VerificationRecords verifications;

    @Test
    @DisplayName("AC-AGT-62.12 a run read shows every stage, and the commit and the verification once they exist")
    void aRunShowsItsStagesAndVerification() throws Exception {
        UUID id = runs.save(new Run("requirement")).getId();

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages.length()").value(8))
                .andExpect(jsonPath("$.stages[0].stage").value("requirements"))
                .andExpect(jsonPath("$.stages[0].state").value("NOT_STARTED"))
                .andExpect(jsonPath("$.commit").doesNotExist())
                .andExpect(jsonPath("$.verification").doesNotExist());

        verifications.record(id, new VerificationResult(Outcome.PASSED, 3, 3, 0, List.of(), "", "abc123"));

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.commit").value("abc123"))
                .andExpect(jsonPath("$.verification.outcome").value("PASSED"))
                .andExpect(jsonPath("$.verification.testsRun").value(3));
    }

    @Test
    @DisplayName("AC-AGT-62.13 an unknown artifact, or an artifact of an unknown run, is a 404")
    void anUnknownArtifactIsNotFound() throws Exception {
        UUID id = runs.save(new Run("requirement")).getId();

        mvc.perform(get("/v1/runs/" + id + "/artifacts/design/1")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/runs/" + UUID.randomUUID() + "/artifacts/design/1")).andExpect(status().isNotFound());
    }
}
