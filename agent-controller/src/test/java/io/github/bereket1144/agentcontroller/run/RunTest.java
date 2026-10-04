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
}
