package io.github.bereket1144.agentcontroller.metrics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(MetricsController.METRICS_PATH)
public class MetricsController {

    static final String METRICS_PATH = "/v1/metrics";

    private final MetricsService metrics;

    public MetricsController(MetricsService metrics) {
        this.metrics = metrics;
    }

    @Operation(summary = "Read the reliability numbers", description = "Computes, from the stored runs, model calls "
            + "and verification results: the runs per status and failure code, the success rate, the latency of "
            + "finished runs (waiting for people included), the model calls with their retries, repairs and "
            + "fallbacks, the reported tokens and the verification pass rate. Mean time to recovery and rollback "
            + "frequency are null with a note. Rates and latencies are null when there is no data.")
    @ApiResponse(responseCode = "200", description = "The numbers; numbers and fixed labels only.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MetricsResponse.class)))
    @GetMapping
    public MetricsResponse getMetrics() {
        return metrics.compute();
    }
}
