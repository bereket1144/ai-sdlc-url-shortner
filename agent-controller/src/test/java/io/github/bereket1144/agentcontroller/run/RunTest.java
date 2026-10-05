package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// Plain unit test: no Spring, no database.
class RunTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("AC-RUN-2.5 creating a run with a null or blank requirement fails with IllegalArgumentException")
    void rejectsBlankRequirements(String requirement) {
        assertThatThrownBy(() -> new Run(requirement))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requirement must not be blank");
    }

    @Test
    @DisplayName("AC-FLOW-1.6 run creation without a kind defaults to GREENFIELD")
    void defaultsToGreenfield() {
        assertThat(new Run("Build a task tracker").getKind()).isEqualTo(RunKind.GREENFIELD);
    }

    @Test
    @DisplayName("AC-FLOW-1.6 a run cannot be created without a kind")
    void rejectsMissingKind() {
        assertThatThrownBy(() -> new Run("Build a task tracker", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("kind must not be null");
    }

    @Test
    @DisplayName("a CREATED run can be started and becomes RUNNING")
    void startMovesCreatedToRunning() {
        Run run = new Run("Build a task tracker");

        run.start();

        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("a run that is already RUNNING cannot be started again")
    void startRejectsARunThatIsNotCreated() {
        Run run = new Run("Build a task tracker");
        run.start();

        assertThatThrownBy(run::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a CREATED run can be started");
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("AC-EXEC-3.1 a RUNNING run can become SUCCEEDED")
    void succeedMovesRunningToSucceeded() {
        Run run = new Run("Build a task tracker");
        run.start();

        run.succeed();

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("AC-EXEC-3.1 only a RUNNING run can become SUCCEEDED")
    void succeedRejectsOtherStatuses() {
        Run created = new Run("Build a task tracker");
        assertThatThrownBy(created::succeed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a RUNNING run can succeed");
        assertThat(created.getStatus()).isEqualTo(RunStatus.CREATED);

        Run succeeded = new Run("Build a task tracker");
        succeeded.start();
        succeeded.succeed();
        assertThatThrownBy(succeeded::succeed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a RUNNING run can succeed");
        assertThat(succeeded.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("AC-EXEC-5.1 domain transition allows a RUNNING run to become FAILED")
    void failMovesRunningToFailed() {
        Run run = new Run("Build a task tracker");
        run.start();

        run.fail();

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo(RunFailureReason.UNEXPECTED);
    }

    @Test
    @DisplayName("AC-AGT-9.3 a FAILED transition requires a reason and sets it with the status")
    void failureReasonIsPartOfTransition() {
        Run run = new Run("Build a task tracker");
        run.start();

        assertThatThrownBy(() -> run.fail(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getFailureReason()).isNull();

        run.fail(RunFailureReason.OUTPUT_INVALID);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo(RunFailureReason.OUTPUT_INVALID);
    }

    @Test
    @DisplayName("AC-EXEC-5.3 only a RUNNING run can become FAILED")
    void failRejectsOtherStatuses() {
        Run created = new Run("Build a task tracker");
        Run succeeded = new Run("Build a task tracker");
        succeeded.start();
        succeeded.succeed();
        Run failed = new Run("Build a task tracker");
        failed.start();
        failed.fail();

        for (Run run : List.of(created, succeeded, failed)) {
            RunStatus originalStatus = run.getStatus();
            assertThatThrownBy(run::fail)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Only a RUNNING run can fail");
            assertThat(run.getStatus()).isEqualTo(originalStatus);
        }
    }

    @Test
    @DisplayName("AC-AGT-66.4 a retry needs a named start point, because a run with none would be taken for a first attempt")
    void retryNeedsAStartPoint() {
        Run failed = new Run("Build a task tracker");
        failed.start();
        failed.fail();

        assertThatThrownBy(() -> failed.retry(" ", 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startsAt must not be blank");
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getAttempt()).isEqualTo(1);
        assertThat(failed.getResumedFrom()).isNull();
    }
}
