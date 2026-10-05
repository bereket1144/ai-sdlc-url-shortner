package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// AGT-50 on the full application and the real PostgreSQL schema. The background scheduler is off in this context, so a
// run that is created stays CREATED and the test sees exactly what the request stored.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RunBaseFullStackTest {

    private static final String REQUIREMENT = "Change the product";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunRepository runs;

    @Autowired
    private VerificationRecords verifications;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> created = new ArrayList<>();

    // The rows of the runs this test made, newest first: a run that changes another is deleted before its base, so the
    // foreign key from base_run_id holds at every delete.
    @AfterEach
    void deleteWhatTheTestCreated() {
        for (int index = created.size() - 1; index >= 0; index--) {
            jdbc.update("delete from verification_result where run_id = ?", created.get(index));
            jdbc.update("delete from runs where id = ?", created.get(index));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"requirement\":\"x\",\"kind\":\"SIDEWAYS\"}",
            "{\"requirement\":\"x\",\"kind\":\"BROWNFIELD\"}",
            "{\"requirement\":\"x\",\"baseRunId\":\"%BASE%\"}",
            "{\"requirement\":\"x\",\"kind\":\"GREENFIELD\",\"baseRunId\":\"%BASE%\"}",
            "{\"requirement\":\"x\",\"kind\":\"BROWNFIELD\",\"baseRunId\":\"not-a-uuid\"}",
            "{\"requirement\":\"x\",\"kind\":\"BROWNFIELD\",\"baseRunId\":\"/workspaces/some-run\"}",
            "{\"requirement\":\"x\",\"kind\":\"BROWNFIELD\",\"baseRunId\":\"https://example.invalid/repo.git\"}"})
    @DisplayName("AC-AGT-50.1, 50.2 an unknown kind, a BROWNFIELD run without a base, a GREENFIELD run with one, and "
            + "a base that is not a run id (a path or an address) are each refused with 400 problem+json and store nothing")
    void aBadKindOrBaseIsRefused(String body) throws Exception {
        UUID base = succeededRunWithCommit();
        long before = runs.count();

        expectBadRequest(mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("%BASE%", base.toString()))));

        assertThat(runs.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-AGT-50.2 a base run that does not exist, has not SUCCEEDED, or has no stored commit is refused "
            + "with 400 problem+json and nothing is stored")
    void aBaseThatCannotBeChangedIsRefused() throws Exception {
        Run failed = new Run("a failed run");
        failed.start();
        failed.fail();
        UUID failedId = save(failed);
        Run succeeded = new Run("a run that succeeded without a commit");
        succeeded.start();
        succeeded.succeed();
        UUID withoutCommit = save(succeeded);
        long before = runs.count();

        expectBadRequest(change(UUID.randomUUID())).andExpect(jsonPath("$.detail").value("The base run was not found."));
        expectBadRequest(change(failedId)).andExpect(jsonPath("$.detail").value("The base run has not SUCCEEDED."));
        expectBadRequest(change(withoutCommit))
                .andExpect(jsonPath("$.detail").value("The base run has no stored commit."));

        assertThat(runs.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-AGT-50.1, 50.2, 50.4 a BROWNFIELD run on a base that SUCCEEDED and has a commit is created, stores "
            + "its kind and base run, and reads back with both")
    void aChangeRunStoresItsBase() throws Exception {
        UUID base = succeededRunWithCommit();

        String body = change(base).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        created.add(id);

        Run stored = runs.findById(id).orElseThrow();
        assertThat(stored.getKind()).isEqualTo(RunKind.BROWNFIELD);
        assertThat(stored.getBaseRunId()).isEqualTo(base);
        mvc.perform(get("/v1/runs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("BROWNFIELD"))
                .andExpect(jsonPath("$.baseRunId").value(base.toString()));
    }

    private ResultActions change(UUID base) throws Exception {
        return mvc.perform(post("/v1/runs").contentType(MediaType.APPLICATION_JSON).content(
                "{\"requirement\":\"" + REQUIREMENT + "\",\"kind\":\"BROWNFIELD\",\"baseRunId\":\"" + base + "\"}"));
    }

    private UUID succeededRunWithCommit() {
        Run run = new Run("The earlier run");
        run.start();
        run.succeed();
        UUID id = save(run);
        verifications.record(id, new VerificationResult(Outcome.PASSED, 1, 1, 0, List.of(), "", "abc123"));
        return id;
    }

    private UUID save(Run run) {
        UUID id = runs.save(run).getId();
        created.add(id);
        return id;
    }

    private static ResultActions expectBadRequest(ResultActions result) throws Exception {
        return result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + 400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isString());
    }
}
