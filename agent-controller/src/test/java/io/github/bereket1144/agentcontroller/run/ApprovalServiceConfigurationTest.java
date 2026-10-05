package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.workflow.StageProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Plain unit test: no Spring, no database.
class ApprovalServiceConfigurationTest {

    @Test
    @DisplayName("AC-AGT-15.1 a stage other than design or planning in agent.approval.required-for, or a limit below 1, "
            + "stops the start; an empty list, spaces and either stage are accepted")
    void aWrongSettingStopsTheStart() {
        assertThatThrownBy(() -> service("design, development", 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("development");
        assertThatThrownBy(() -> service("design", 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent.approval.max-rejections");

        assertThatCode(() -> service("", 3)).doesNotThrowAnyException();
        assertThatCode(() -> service(" planning , design ,", 1)).doesNotThrowAnyException();
    }

    private static ApprovalService service(String requiredFor, int maxRejections) {
        return new ApprovalService(mock(RunRepository.class), mock(ApprovalRepository.class),
                mock(ArtifactStore.class), mock(RoleCatalog.class), mock(StageProgress.class), requiredFor,
                maxRejections);
    }
}
