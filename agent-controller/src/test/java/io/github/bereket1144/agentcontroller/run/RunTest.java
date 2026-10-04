package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
