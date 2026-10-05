package io.github.bereket1144.agentcontroller.metrics;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.AttemptKind;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// AGT-46 on the full application and the real PostgreSQL schema. Runs and model calls are inserted with SQL so that
// their times and token counts are exact. The numbers cover the whole database, so each test starts from an empty
// one and leaves it empty.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MetricsFullStackTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final String REQUIREMENT = "a private requirement text";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private VerificationRecords verifications;

    @BeforeEach
    void startEmpty() {
        emptyTheDatabase();
    }

    @AfterEach
    void leaveEmpty() {
        emptyTheDatabase();
    }

    // Children first, so that the foreign keys to runs hold at every delete.
    private void emptyTheDatabase() {
        for (String table : List.of("verification_result", "run_stage", "artifact", "model_call",
                "model_budget_reservation", "runs")) {
            jdbc.update("delete from " + table);
        }
    }

    @Test
    @DisplayName("AC-AGT-46.2 with no runs every count is 0, every rate and latency is null, and the answer is a 200")
    void anEmptyDatabaseGivesZerosAndNulls() throws Exception {
        mvc.perform(get("/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.total").value(0))
                .andExpect(jsonPath("$.runs.byStatus.*", everyItem(is(0))))
                .andExpect(jsonPath("$.runs.byFailureReason.*", everyItem(is(0))))
                .andExpect(jsonPath("$.runs.successRate").value(nullValue()))
                .andExpect(jsonPath("$.latencySeconds.average").value(nullValue()))
                .andExpect(jsonPath("$.latencySeconds.median").value(nullValue()))
                .andExpect(jsonPath("$.latencySeconds.p95").value(nullValue()))
                .andExpect(jsonPath("$.modelCalls.total").value(0))
                .andExpect(jsonPath("$.modelCalls.perRunAverage").value(nullValue()))
                .andExpect(jsonPath("$.modelCalls.retries.count").value(0))
                .andExpect(jsonPath("$.modelCalls.retries.share").value(nullValue()))
                .andExpect(jsonPath("$.modelCalls.repairs.share").value(nullValue()))
                .andExpect(jsonPath("$.modelCalls.fallbacks.share").value(nullValue()))
                .andExpect(jsonPath("$.modelCalls.reportedPromptTokens").value(0))
                .andExpect(jsonPath("$.modelCalls.reportedAnswerTokens").value(0))
                .andExpect(jsonPath("$.verification.total").value(0))
                .andExpect(jsonPath("$.verification.passRate").value(nullValue()));
    }

    @Test
    @DisplayName("AC-AGT-46.1, AC-AGT-46.3 stored runs, calls and results give the right counts, rates, latency and tokens, and the answer holds no run text or id")
    void storedRowsGiveTheNumbers() throws Exception {
        UUID withCalls = run("SUCCEEDED", null, 10);
        run("SUCCEEDED", null, 20);
        run("SUCCEEDED", null, 30);
        run("FAILED", "BUDGET_EXHAUSTED", 40);
        run("RUNNING", null, 5); // not finished: counted as a run, left out of the latency
        for (int i = 0; i < 6; i++) {
            modelCall(withCalls, AttemptKind.FIRST, 100L, 10L);
        }
        modelCall(withCalls, AttemptKind.REPAIR, 100L, 10L);
        modelCall(withCalls, AttemptKind.REPAIR, 100L, 10L);
        modelCall(withCalls, AttemptKind.RETRY, 100L, 10L);
        modelCall(withCalls, AttemptKind.FALLBACK, null, null); // the call failed: it reported no tokens
        for (Outcome outcome : List.of(Outcome.PASSED, Outcome.PASSED, Outcome.PASSED, Outcome.FAILED)) {
            verifications.record(withCalls, new VerificationResult(outcome, 1, 1, 0, List.of(), "", "abc123"));
        }

        mvc.perform(get("/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs.total").value(5))
                .andExpect(jsonPath("$.runs.byStatus.SUCCEEDED").value(3))
                .andExpect(jsonPath("$.runs.byStatus.FAILED").value(1))
                .andExpect(jsonPath("$.runs.byStatus.RUNNING").value(1))
                .andExpect(jsonPath("$.runs.byStatus.CREATED").value(0))
                .andExpect(jsonPath("$.runs.successRate").value(closeTo(0.75, 0.0001)))
                .andExpect(jsonPath("$.runs.byFailureReason.BUDGET_EXHAUSTED").value(1))
                .andExpect(jsonPath("$.runs.byFailureReason.UNEXPECTED").value(0))
                // 10, 20, 30 and 40 seconds: the 95th percentile lies between 30 and 40, at 85% of the way
                .andExpect(jsonPath("$.latencySeconds.average").value(closeTo(25.0, 0.001)))
                .andExpect(jsonPath("$.latencySeconds.median").value(closeTo(25.0, 0.001)))
                .andExpect(jsonPath("$.latencySeconds.p95").value(closeTo(38.5, 0.001)))
                .andExpect(jsonPath("$.latencySeconds.note").value(containsString("waiting for people")))
                .andExpect(jsonPath("$.modelCalls.total").value(10))
                .andExpect(jsonPath("$.modelCalls.perRunAverage").value(closeTo(2.0, 0.0001)))
                .andExpect(jsonPath("$.modelCalls.retries.count").value(1))
                .andExpect(jsonPath("$.modelCalls.retries.share").value(closeTo(0.1, 0.0001)))
                .andExpect(jsonPath("$.modelCalls.repairs.count").value(2))
                .andExpect(jsonPath("$.modelCalls.repairs.share").value(closeTo(0.2, 0.0001)))
                .andExpect(jsonPath("$.modelCalls.fallbacks.count").value(1))
                .andExpect(jsonPath("$.modelCalls.fallbacks.share").value(closeTo(0.1, 0.0001)))
                .andExpect(jsonPath("$.modelCalls.reportedPromptTokens").value(900))
                .andExpect(jsonPath("$.modelCalls.reportedAnswerTokens").value(90))
                .andExpect(jsonPath("$.verification.total").value(4))
                .andExpect(jsonPath("$.verification.passRate").value(closeTo(0.75, 0.0001)))
                .andExpect(jsonPath("$.meanTimeToRecovery.value").value(nullValue()))
                .andExpect(jsonPath("$.meanTimeToRecovery.note").value(containsString("no recovery")))
                .andExpect(jsonPath("$.rollbackFrequency.value").value(nullValue()))
                .andExpect(jsonPath("$.rollbackFrequency.note").value(containsString("no rollback")))
                .andExpect(content().string(not(containsString(REQUIREMENT))))
                .andExpect(content().string(not(containsString(withCalls.toString()))));
    }

    private UUID run(String status, String failureReason, long seconds) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into runs (id, requirement, kind, status, failure_reason, created_at, updated_at, version) "
                        + "values (?, ?, 'GREENFIELD', ?, ?, ?, ?, 0)",
                id, REQUIREMENT, status, failureReason, Timestamp.from(START), Timestamp.from(START.plusSeconds(seconds)));
        return id;
    }

    private void modelCall(UUID runId, AttemptKind kind, Long promptTokens, Long answerTokens) {
        jdbc.update("insert into model_call (run_id, role, role_version, profile, model, attempt_kind, "
                        + "input_fingerprint, system_text, user_text, estimated_prompt_tokens, reported_prompt_tokens, "
                        + "reported_answer_tokens, reduction_steps, duration_ms, outcome) values (?, 'requirements', "
                        + "'1', 'local', 'test-model', ?, ?, 'system', 'user', 0, ?, ?, '', 0, 'SUCCESS')",
                runId, kind.name(), "a".repeat(64), promptTokens, answerTokens);
    }
}
