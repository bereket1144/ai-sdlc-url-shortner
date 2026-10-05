package io.github.bereket1144.agentcontroller.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.InputAccessDeniedException;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.run.RunFailureReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class StageInputsAccessTest {

    @Test
    @DisplayName("AC-AGT-16.2 asking for an input the role does not list fails with ACCESS_DENIED and reads no content")
    void anUnlistedInputIsDeniedBeforeAnythingIsRead() throws IOException {
        ArtifactStore store = mock(ArtifactStore.class);
        RoleCatalog roles = new RoleCatalog(
                new PathMatchingResourcePatternResolver().getResources("classpath*:/agent-roles/*.properties"));
        StageInputs stageInputs = new StageInputs(store, roles);
        // The requirements role lists only the original requirement and the questions and answers.
        List<ArtifactRef> refs = List.of(new ArtifactRef("design", 1, "a".repeat(64)));

        assertThatThrownBy(() -> stageInputs.resolve(UUID.randomUUID(), "requirements", refs))
                .isInstanceOfSatisfying(InputAccessDeniedException.class, denied -> {
                    assertThat(denied.code()).isEqualTo("ACCESS_DENIED");
                    assertThat(denied.roleId()).isEqualTo("requirements");
                    assertThat(denied.kind()).isEqualTo(InputKind.DESIGN_ARTIFACT);
                    assertThat(RunFailureReason.from(denied)).isEqualTo(RunFailureReason.ACCESS_DENIED);
                });
        verifyNoInteractions(store);
    }
}
