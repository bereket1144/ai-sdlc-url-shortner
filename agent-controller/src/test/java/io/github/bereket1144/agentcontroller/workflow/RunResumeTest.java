package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.run.RunKind;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.json.JsonMapper;

/** Plain unit tests: the stored artifacts and the stored check are stand-ins, and the roles are the real ones. */
class RunResumeTest {

    private static final String REQUIREMENT = "Build a task tracker";

    private final UUID runId = UUID.randomUUID();
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final VerificationRecords verifications = mock(VerificationRecords.class);
    private RunResume resume;

    @BeforeEach
    void setUp() throws IOException {
        RoleCatalog roles = new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties"));
        resume = new RunResume(artifacts, roles, verifications);
    }

    static Stream<Arguments> startPoints() {
        return Stream.of(
                Arguments.of("a stage that failed",
                        Map.of("requirements", "FINISHED", "design", "FINISHED", "planning", "FINISHED",
                                "development", "FAILED"), RunKind.GREENFIELD, "development"),
                Arguments.of("impact analysis is skipped for a greenfield run",
                        Map.of("requirements", "FINISHED"), RunKind.GREENFIELD, "design"),
                Arguments.of("impact analysis is a stage of a brownfield run",
                        Map.of("requirements", "FINISHED"), RunKind.BROWNFIELD, "impactAnalysis"),
                Arguments.of("a stage that was left RUNNING is not finished",
                        Map.of("requirements", "RUNNING"), RunKind.GREENFIELD, "START"),
                Arguments.of("a run that recorded no stage starts from the beginning",
                        Map.<String, String>of(), RunKind.GREENFIELD, "START"),
                Arguments.of("the first stage of the group that is not finished",
                        Map.of("requirements", "FINISHED", "design", "FINISHED", "planning", "FINISHED",
                                "development", "FINISHED", "qa", "FINISHED", "review", "FAILED",
                                "documentation", "RUNNING"), RunKind.GREENFIELD, "review"),
                Arguments.of("every stage finished: the run failed after the join, so the group runs again",
                        Map.of("requirements", "FINISHED", "design", "FINISHED", "planning", "FINISHED",
                                "development", "FINISHED", "qa", "FINISHED", "review", "FINISHED",
                                "documentation", "FINISHED"), RunKind.GREENFIELD, "qa"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startPoints")
    @DisplayName("AC-AGT-66.2 the first retry starts at the earliest stage, in graph order, that is not FINISHED")
    void theFirstRetryStartsAtTheEarliestUnfinishedStage(String why, Map<String, String> states, RunKind kind,
                                                         String expected) {
        assertThat(RunResume.startPoint(states, kind)).isEqualTo(expected);
    }

    @Test
    @DisplayName("AC-AGT-66.2 the state of a stage is rebuilt from the latest version of each earlier artifact, the "
            + "marker of a stage that stored none, and nothing of the stage it starts at")
    void theStateBeforeAStageIsRebuiltFromWhatIsStored() {
        when(artifacts.forRun(runId)).thenReturn(List.of(
                stored("requirements", 1, "batch"), stored("requirements", 2, "stories"),
                stored("design", 1, "first"), stored("design", 2, "second"),
                stored("development", 1, "code")));

        Map<String, Object> input = resume.input(runId, REQUIREMENT, RunKind.GREENFIELD, "development");

        // The planning stage ran on its dummy node: it stored no plan and left its marker. The kind of the plan is "plan".
        assertThat(input).containsOnlyKeys("runId", "requirement", "kind", "requirements", "design", "planning")
                .containsEntry("requirements", new ArtifactRef("requirements", 2, "stories"))
                .containsEntry("design", new ArtifactRef("design", 2, "second"))
                .containsEntry("planning", "planning:done")
                .containsEntry("runId", runId).containsEntry("requirement", REQUIREMENT)
                .containsEntry("kind", RunKind.GREENFIELD);
    }

    @Test
    @DisplayName("AC-AGT-66.2 the group starts after development, with the commit and the outcome of its check, "
            + "and it starts as a group whichever of its stages is named")
    void theGroupStartsAfterDevelopment() {
        when(artifacts.forRun(runId)).thenReturn(List.of(
                stored("requirements", 1, "stories"), stored("design", 1, "design"), stored("plan", 1, "plan"),
                stored("development", 3, "code"), stored("qa", 1, "qa")));
        when(verifications.latest(runId)).thenReturn(Optional.of(
                new VerificationResult(Outcome.PASSED, 2, 2, 0, List.of(), "", "c1")));

        Map<String, Object> input = resume.input(runId, REQUIREMENT, RunKind.BROWNFIELD, "review");

        // A brownfield run holds an impact analysis; here none was stored, so its marker stands in.
        assertThat(input).containsOnlyKeys("runId", "requirement", "kind", "requirements", "impactAnalysis", "design",
                        "planning", "development", "commit", "verification")
                .containsEntry("impactAnalysis", "impactAnalysis:done")
                .containsEntry("planning", new ArtifactRef("plan", 1, "plan"))
                .containsEntry("development", new ArtifactRef("development", 3, "code"))
                .containsEntry("commit", "c1").containsEntry("verification", "PASSED");
    }

    @Test
    @DisplayName("AC-AGT-66.3 an attempt from the beginning carries the commit of the latest check, so that "
            + "development writes its code afresh")
    void aRestartCarriesTheCommitOfTheLatestCheck() {
        when(verifications.latest(runId)).thenReturn(Optional.of(
                new VerificationResult(Outcome.FAILED, 2, 1, 1, List.of("t"), "", "c1")));

        Map<String, Object> input = resume.input(runId, REQUIREMENT, RunKind.GREENFIELD, RunResume.START);

        assertThat(input).containsOnlyKeys("runId", "requirement", "kind", DevelopmentFinish.PREVIOUS_ATTEMPT_COMMIT)
                .containsEntry(DevelopmentFinish.PREVIOUS_ATTEMPT_COMMIT, "c1");
    }

    @Test
    @DisplayName("AC-AGT-66.2 a start point that is not a stage a retry can start at is refused")
    void anUnknownStartPointIsRefused() {
        assertThatThrownBy(() -> resume.input(runId, REQUIREMENT, RunKind.GREENFIELD, "requirements"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A retry cannot start at requirements");
    }

    private StoredArtifact stored(String kind, int version, String fingerprint) {
        return new StoredArtifact(runId, kind, version, JsonMapper.shared().createObjectNode(), "summary",
                fingerprint, kind, "1", "local", "model", List.of());
    }
}
