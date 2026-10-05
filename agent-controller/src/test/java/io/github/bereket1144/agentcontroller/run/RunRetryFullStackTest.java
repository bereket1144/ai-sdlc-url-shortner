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

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// AGT-66 on the full application and the real PostgreSQL schema. The background scheduler is off in this context, so a
// run that is queued again stays CREATED and the test sees exactly what the request changed.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RunRetryFullStackTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RunRepository runs;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> created = new ArrayList<>();

    // The rows of the runs this test made, children first so that the foreign key to runs holds at every delete.
    @AfterEach
    void deleteWhatTheTestCreated() {
        for (UUID id : created) {
            jdbc.update("delete from run_stage where run_id = ?", id);
            jdbc.update("delete from runs where id = ?", id);
        }
    }

    @Test
    @DisplayName("AC-AGT-66.1, 66.4 a FAILED run with attempts left is queued again: 202 with its id and attempt, "
            + "CREATED, the failure reason cleared and the attempt starting at the earliest stage that is not finished")
    void aFailedRunIsQueuedAgain() throws Exception {
        UUID id = failedRun(1);
        jdbc.update("insert into run_stage (run_id, stage, state) values (?, 'requirements', 'FINISHED')", id);

        mvc.perform(post("/v1/runs/" + id + "/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.attempt").value(2));

        mvc.perform(get("/v1/runs/" + id))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.attempt").value(2))
                .andExpect(jsonPath("$.resumedFrom").value("design"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    @DisplayName("AC-AGT-66.1 an unknown run is 404 in the problem format")
    void anUnknownRunIsNotFound() throws Exception {
        expectProblem(mvc.perform(post("/v1/runs/" + UUID.randomUUID() + "/retry")), 404)
                .andExpect(jsonPath("$.detail").value("Run not found."));
    }

    @Test
    @DisplayName("AC-AGT-66.1 a run that is not FAILED is refused with 409 in the problem format and is not changed")
    void aRunThatIsNotFailedIsRefused() throws Exception {
        UUID id = runs.save(new Run("requirement")).getId();
        created.add(id);
        Long version = runs.findById(id).orElseThrow().getVersion();

        expectProblem(mvc.perform(post("/v1/runs/" + id + "/retry")), 409)
                .andExpect(jsonPath("$.detail").value("Only a failed run can be retried."));

        assertUnchanged(id, RunStatus.CREATED, 1, version);
    }

    @Test
    @DisplayName("AC-AGT-66.1, 66.5 a FAILED run with no attempts left is refused with 409 and is not changed")
    void aRunWithNoAttemptsLeftIsRefused() throws Exception {
        UUID id = failedRun(3);
        Long version = runs.findById(id).orElseThrow().getVersion();

        expectProblem(mvc.perform(post("/v1/runs/" + id + "/retry")), 409)
                .andExpect(jsonPath("$.detail").value("The run has no attempts left."));

        assertUnchanged(id, RunStatus.FAILED, 3, version);
    }

    /** A saved run that is FAILED on the given attempt, brought there through its own state changes. */
    private UUID failedRun(int attempt) {
        Run run = new Run("requirement");
        run.start();
        run.fail(RunFailureReason.VERIFICATION_FAILED);
        for (int earlier = 1; earlier < attempt; earlier++) {
            run.retry("START", attempt);
            run.start();
            run.fail(RunFailureReason.VERIFICATION_FAILED);
        }
        UUID id = runs.save(run).getId();
        created.add(id);
        return id;
    }

    private void assertUnchanged(UUID id, RunStatus status, int attempt, Long version) {
        Run run = runs.findById(id).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(status);
        assertThat(run.getAttempt()).isEqualTo(attempt);
        assertThat(run.getVersion()).isEqualTo(version);
    }

    private static ResultActions expectProblem(ResultActions result, int status) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(ApiExceptionHandler.TYPE_PREFIX + status))
                .andExpect(jsonPath("$.status").value(status));
    }
}
