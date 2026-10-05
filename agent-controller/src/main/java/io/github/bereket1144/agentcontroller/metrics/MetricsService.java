package io.github.bereket1144.agentcontroller.metrics;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.bereket1144.agentcontroller.metrics.MetricsResponse.CountAndShare;
import io.github.bereket1144.agentcontroller.metrics.MetricsResponse.LatencySeconds;
import io.github.bereket1144.agentcontroller.metrics.MetricsResponse.ModelCallCounts;
import io.github.bereket1144.agentcontroller.metrics.MetricsResponse.NotMeasured;
import io.github.bereket1144.agentcontroller.metrics.MetricsResponse.RunCounts;
import io.github.bereket1144.agentcontroller.metrics.MetricsResponse.VerificationCounts;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.AttemptKind;
import io.github.bereket1144.agentcontroller.run.RunFailureReason;
import io.github.bereket1144.agentcontroller.run.RunStatus;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Computes the reliability numbers from what the platform already stores: runs, model calls and verification
 * results. It only reads. The database counts and sums; the average, the median and the 95th percentile are worked
 * out here, from a plain list, so no database-specific function is needed.
 */
@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    static final String LATENCY_NOTE = "Seconds from the creation of a run to its end. The time a run spent waiting "
            + "for people is included.";
    static final String NO_RECOVERY = "The platform has no recovery yet, so this is not measured.";
    static final String NO_ROLLBACK = "The platform has no rollback yet, so this is not measured.";

    private final JdbcTemplate jdbc;

    public MetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MetricsResponse compute() {
        Map<String, Long> byStatus = counts("select status, count(*) from runs group by status", RunStatus.values());
        Map<String, Long> byReason = counts("select failure_reason, count(*) from runs "
                + "where failure_reason is not null group by failure_reason", RunFailureReason.values());
        Map<String, Long> byAttempt = counts("select attempt_kind, count(*) from model_call group by attempt_kind",
                AttemptKind.values());
        Map<String, Long> byOutcome = counts("select outcome, count(*) from verification_result group by outcome",
                Outcome.values());

        long runs = total(byStatus);
        long succeeded = byStatus.get(RunStatus.SUCCEEDED.name());
        long finished = succeeded + byStatus.get(RunStatus.FAILED.name());
        long calls = total(byAttempt);
        long results = total(byOutcome);
        // The sum is over the calls that reported a count; a call that failed before an answer reported none.
        long[] tokens = jdbc.queryForObject("select coalesce(sum(reported_prompt_tokens), 0), "
                + "coalesce(sum(reported_answer_tokens), 0) from model_call",
                (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)});

        MetricsResponse metrics = new MetricsResponse(
                new RunCounts(runs, byStatus, ratio(succeeded, finished), byReason),
                latency(),
                new ModelCallCounts(calls, ratio(calls, runs), share(byAttempt, AttemptKind.RETRY, calls),
                        share(byAttempt, AttemptKind.REPAIR, calls), share(byAttempt, AttemptKind.FALLBACK, calls),
                        tokens[0], tokens[1]),
                new VerificationCounts(results, ratio(byOutcome.get(Outcome.PASSED.name()), results)),
                new NotMeasured(null, NO_RECOVERY),
                new NotMeasured(null, NO_ROLLBACK));
        log.info("event=metrics_computed runs={} finishedRuns={} modelCalls={} verificationResults={}",
                runs, finished, calls, results);
        return metrics;
    }

    /** Every constant of the enum is in the answer, 0 when the database has no row for it. */
    private <E extends Enum<E>> Map<String, Long> counts(String sql, E[] all) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (E value : all) {
            counts.put(value.name(), 0L);
        }
        jdbc.query(sql, rs -> { counts.put(rs.getString(1), rs.getLong(2)); });
        return counts;
    }

    private LatencySeconds latency() {
        List<Double> seconds = jdbc.query("select created_at, updated_at from runs where status in (?, ?)",
                (rs, row) -> Duration.between(rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()).toMillis() / 1000.0,
                RunStatus.SUCCEEDED.name(), RunStatus.FAILED.name()).stream().sorted().toList();
        if (seconds.isEmpty()) {
            return new LatencySeconds(null, null, null, LATENCY_NOTE);
        }
        double average = seconds.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return new LatencySeconds(average, percentile(seconds, 0.5), percentile(seconds, 0.95), LATENCY_NOTE);
    }

    /** The value at the given fraction of a sorted, non-empty list, between the two nearest entries (linear). */
    private static double percentile(List<Double> sorted, double fraction) {
        double position = fraction * (sorted.size() - 1);
        int below = (int) Math.floor(position);
        int above = (int) Math.ceil(position);
        return sorted.get(below) + (sorted.get(above) - sorted.get(below)) * (position - below);
    }

    private static CountAndShare share(Map<String, Long> byAttempt, AttemptKind kind, long calls) {
        long count = byAttempt.get(kind.name());
        return new CountAndShare(count, ratio(count, calls));
    }

    private static long total(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Null when there is nothing to divide by, so the answer never fails for lack of data. */
    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : (double) part / whole;
    }
}
