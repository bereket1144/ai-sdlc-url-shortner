package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
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
}
