package io.github.bereket1144.agentcontroller.metrics;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The reliability numbers. It holds numbers and fixed labels only (the names of run statuses, failure codes and
 * fixed notes): no requirement, question, answer, model text or run id. A rate or a latency is null when there is
 * nothing to measure it from.
 */
public record MetricsResponse(RunCounts runs, LatencySeconds latencySeconds, ModelCallCounts modelCalls,
                              VerificationCounts verification, NotMeasured meanTimeToRecovery,
                              NotMeasured rollbackFrequency) {

    @Schema(name = "MetricsRuns", description = "How many runs there are, per status, and how many finished runs "
            + "succeeded. Every status and every failure code is listed, with 0 when there is none.")
    public record RunCounts(long total, Map<String, Long> byStatus,
                            @Schema(description = "SUCCEEDED over SUCCEEDED and FAILED runs; null when none has finished.")
                            Double successRate,
                            Map<String, Long> byFailureReason) { }

    @Schema(name = "MetricsLatency", description = "Seconds from the creation of a run to its end, for runs that "
            + "SUCCEEDED or FAILED. All three are null when no run has finished.")
    public record LatencySeconds(Double average, Double median, Double p95, String note) { }

    @Schema(name = "MetricsModelCalls", description = "Every stored model call. A retry, a repair and a fallback each "
            + "has its count and its share of all calls. The token sums are of the counts the model reported.")
    public record ModelCallCounts(long total,
                                  @Schema(description = "Calls over all runs, including runs that made none; null when there is no run.")
                                  Double perRunAverage,
                                  CountAndShare retries, CountAndShare repairs, CountAndShare fallbacks,
                                  long reportedPromptTokens, long reportedAnswerTokens) { }

    @Schema(name = "MetricsCountAndShare", description = "How many, and that number over all calls; the share is "
            + "null when there is no call.")
    public record CountAndShare(long count, Double share) { }

    @Schema(name = "MetricsVerification", description = "Every stored build and test result, and PASSED over all of "
            + "them; the rate is null when there is none.")
    public record VerificationCounts(long total, Double passRate) { }

    @Schema(name = "MetricsNotMeasured", description = "A number the platform cannot measure yet: the value is null "
            + "and the note says why.")
    public record NotMeasured(Double value, String note) { }
}
