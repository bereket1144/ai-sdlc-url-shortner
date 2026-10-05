package io.github.bereket1144.agentcontroller.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Remembers which stage of a run is running or finished, so the run can be read while it goes on. */
@Service
public class StageProgress {

    /** The stages in the order a client sees them. */
    public static final List<String> STAGES = List.of("requirements", "impactAnalysis", "design", "planning",
            "development", "qa", "review", "documentation");

    private final JdbcTemplate jdbc;

    public StageProgress(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void started(UUID runId, String stage) {
        write(runId, stage, "RUNNING");
    }

    void finished(UUID runId, String stage) {
        write(runId, stage, "FINISHED");
    }

    /** The stage failed and is not attempted again. A stage that is attempted again is {@link #started} again. */
    void failed(UUID runId, String stage) {
        write(runId, stage, "FAILED");
    }

    /** Forgets every stage of the run, so an attempt that starts from the beginning shows each stage as not started. */
    void reset(UUID runId) {
        jdbc.update("delete from run_stage where run_id = ?", runId);
    }

    /** The state of every stage that has one: RUNNING, FINISHED or FAILED. A stage that is absent has not started. */
    public Map<String, String> forRun(UUID runId) {
        Map<String, String> states = new HashMap<>();
        jdbc.query("select stage, state from run_stage where run_id = ?",
                rs -> { states.put(rs.getString("stage"), rs.getString("state")); }, runId);
        return states;
    }

    private void write(UUID runId, String stage, String state) {
        jdbc.update("insert into run_stage (run_id, stage, state) values (?, ?, ?) "
                + "on conflict (run_id, stage) do update set state = excluded.state, updated_at = now()",
                runId, stage, state);
    }
}
