package io.github.bereket1144.agentcontroller.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// No Docker and no Spring: the report text goes in, the result comes out.
class JunitReportParserTest {

    private static final String PASSING = """
            <?xml version="1.0" encoding="utf-8"?>
            <testsuites><testsuite name="suite" errors="0" failures="0" skipped="0" tests="2">
            <testcase classname="tests.test_a" name="test_one" time="0.001"/>
            <testcase classname="tests.test_a" name="test_two" time="0.001"/>
            </testsuite></testsuites>
            """;

    // One pass, one failure, one error that has no class name, one skipped test; the failure text is long.
    private static final String FAILING = """
            <testsuites><testsuite name="suite" tests="4">
            <testcase classname="tests.test_a" name="test_ok"/>
            <testcase classname="tests.test_a" name="test_bad"><failure message="assert 1 == 2">%s</failure></testcase>
            <testcase classname="" name="tests.test_broken"><error message="collection failure">x</error></testcase>
            <testcase classname="tests.test_a" name="test_later"><skipped message="not now"/></testcase>
            </testsuite></testsuites>
            """.formatted("x".repeat(5000));

    @Test
    @DisplayName("AC-AGT-62.9 a passing and a failing report give the result with the commit id, the counts, "
            + "the failing test names and a bounded excerpt")
    void reportsAreMappedToTheResult() {
        VerificationResult passing = JunitReportParser.parse(PASSING, "c1");
        assertThat(passing)
                .returns(Outcome.PASSED, VerificationResult::outcome)
                .returns(2, VerificationResult::testsRun)
                .returns(2, VerificationResult::testsPassed)
                .returns(0, VerificationResult::testsFailed)
                .returns("c1", VerificationResult::commitId)
                .returns(true, VerificationResult::passed);
        assertThat(passing.failingTests()).isEmpty();

        VerificationResult failing = JunitReportParser.parse(FAILING, "c2");
        assertThat(failing)
                .returns(Outcome.FAILED, VerificationResult::outcome)
                .returns(4, VerificationResult::testsRun)
                .returns(1, VerificationResult::testsPassed)
                .returns(2, VerificationResult::testsFailed)
                .returns("c2", VerificationResult::commitId)
                .returns(false, VerificationResult::passed);
        assertThat(failing.failingTests()).isEqualTo(List.of("tests.test_a.test_bad", "tests.test_broken"));
        assertThat(failing.excerpt()).hasSize(JunitReportParser.EXCERPT_LIMIT);
        assertThat(FAILING).startsWith(failing.excerpt());

        // A readable report with no test in it proves nothing, so it is not a pass.
        assertThat(JunitReportParser.parse("<testsuite tests=\"0\"/>", "c3").outcome()).isEqualTo(Outcome.FAILED);
    }
}
