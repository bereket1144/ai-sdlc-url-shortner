package io.github.bereket1144.agentcontroller.sandbox;

import java.util.List;

/**
 * What one build and test of a workspace found, kept with the commit it was run on. It is the common shape every
 * test tool's report is turned into, so nothing outside this package depends on a tool.
 *
 * @param outcome      {@code PASSED} only when the project built and every test passed
 * @param testsRun     how many tests ran, skipped ones included
 * @param testsPassed  how many of them passed
 * @param testsFailed  how many of them failed or errored
 * @param failingTests the names of the failed tests
 * @param excerpt      the start of the raw report, cut to a fixed size; empty when there was no report
 * @param commitId     the commit that was built
 */
public record VerificationResult(
        Outcome outcome,
        int testsRun,
        int testsPassed,
        int testsFailed,
        List<String> failingTests,
        String excerpt,
        String commitId) {

    /** {@code NO_RESULT}: no readable report arrived in time. It is a failure and never a pass. */
    public enum Outcome { PASSED, FAILED, NO_RESULT }

    public VerificationResult {
        failingTests = List.copyOf(failingTests);
    }

    public static VerificationResult noResult(String commitId) {
        return new VerificationResult(Outcome.NO_RESULT, 0, 0, 0, List.of(), "", commitId);
    }

    /** True only for a clear pass; a missing result is not one. */
    public boolean passed() {
        return outcome == Outcome.PASSED;
    }
}
