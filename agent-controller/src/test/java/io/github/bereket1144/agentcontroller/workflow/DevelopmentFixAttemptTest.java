package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.NewArtifact;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.artifact.StageInputs;
import io.github.bereket1144.agentcontroller.artifact.StageInputs.Resolved;
import io.github.bereket1144.agentcontroller.model.ModelCallBudget;
import io.github.bereket1144.agentcontroller.model.ModelClient;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.model.ModelProfiles;
import io.github.bereket1144.agentcontroller.model.OutputValidator;
import io.github.bereket1144.agentcontroller.model.PromptBuilder;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import io.github.bereket1144.agentcontroller.run.RunKind;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRecords;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult;
import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import io.github.bereket1144.agentcontroller.sandbox.VerificationRunner;
import io.github.bereket1144.agentcontroller.workspace.RunWorkspace;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceFiles;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/**
 * The development stage over the whole graph, with the real answer service, workspace (real git) and stage wrapper.
 * The model, the artifact store, the verification records and the container are stand-ins: the first change set is
 * broken and fails its test, the second is fixed and passes.
 */
class DevelopmentFixAttemptTest {

    private static final String FAILING_TEST = "tests.test_app.test_shorten";
    private static final String REPORT = "<testsuite>REPORT_EXCERPT_TEXT</testsuite>";
    private static final String BROKEN = "def shorten(url):\n    return None  # BROKEN_VERSION\n";
    private static final String FIXED = "def shorten(url):\n    return url[:5]  # FIXED_VERSION\n";

    @TempDir
    Path temp;

    private final UUID runId = UUID.randomUUID();
    private final ModelProfile profile = new ModelProfile("local", "model", URI.create("http://localhost:11434"),
            8192, 512, Duration.ofSeconds(2), 1, 2, 0, null);
    private final ModelCallBudget budget = mock(ModelCallBudget.class);
    private final List<String> developmentPrompts = Collections.synchronizedList(new ArrayList<>());
    private final List<NewArtifact> stored = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<VerificationResult> latest = new AtomicReference<>();
    private final VerificationRecords records = mock(VerificationRecords.class);
    private WorkflowRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        RoleCatalog roles = new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties"));
        ArtifactStore store = mock(ArtifactStore.class);
        when(store.store(any())).thenAnswer(call -> {
            NewArtifact artifact = call.getArgument(0);
            stored.add(artifact);
            return new ArtifactRef(artifact.role(), stored.size(), "f".repeat(64));
        });
        when(store.find(eq(runId), eq("development"), anyInt())).thenAnswer(call -> {
            int version = call.getArgument(2);
            return Optional.of(new StoredArtifact(runId, "development", version, stored.get(version - 1).content(),
                    "changes 1", "f".repeat(64), "development", "1", "local", "m", List.of()));
        });
        StageInputs stageInputs = mock(StageInputs.class);
        when(stageInputs.resolve(any(), any(), any()))
                .thenAnswer(call -> new Resolved(List.of(), call.getArgument(2)));
        doAnswer(call -> {
            latest.set(call.getArgument(1));
            return null;
        }).when(records).record(eq(runId), any());
        when(records.latest(runId)).thenAnswer(call -> Optional.ofNullable(latest.get()));

        AtomicInteger developmentCalls = new AtomicInteger();
        ModelClient model = (role, modelProfile, system, user) -> {
            developmentPrompts.add(user);
            String code = developmentCalls.incrementAndGet() == 1 ? BROKEN : FIXED;
            return new ModelResult(JsonMapper.shared().writeValueAsString(Map.of(
                    "changes", List.of(Map.of("path", "app.py", "content", code)),
                    "rationale", "why", "affectedCriteria", List.of(), "errorHandling", "how",
                    "verificationNotes", List.of())), FinishReason.COMPLETE, null, null, null);
        };
        ModelProfiles profiles = mock(ModelProfiles.class);
        when(profiles.forRole(anyString())).thenReturn(profile);
        when(profiles.safetyMarginPercent()).thenReturn(10);
        StructuredAnswerService answers = new StructuredAnswerService(model, profiles,
                new PromptBuilder(profiles, roles, 3), new OutputValidator(roles, List.of()),
                mock(ModelCallRecords.class), budget, 2, Duration.ZERO);
        AgentStages stages = new AgentStages(new MockEnvironment().withProperty("agent.stages.development", "agent"),
                roles, stageInputs, answers, store, Optional.empty());
        // The container reads the committed file: the broken one fails its test, the fixed one passes.
        VerificationRunner container = (workspace, commitId) -> {
            boolean fixed = read(workspace.resolve("app.py")).contains("FIXED_VERSION");
            return fixed ? new VerificationResult(Outcome.PASSED, 1, 1, 0, List.of(), "", commitId)
                    : new VerificationResult(Outcome.FAILED, 1, 0, 1, List.of(FAILING_TEST), REPORT, commitId);
        };
        DevelopmentFinish finish = new DevelopmentFinish(store, new RunWorkspace(temp.resolve("workspaces").toString()),
                container, records, new WorkspaceFiles());
        runner = new WorkflowRunner(new DummySdlcNodes(), Optional.empty(), Optional.of(stages), Optional.of(finish),
                Optional.of(mock(StageProgress.class)), Optional.empty(), 2);
    }

    @Test
    @DisplayName("AC-AGT-65.3 after a failed build the development agent is told the failing test and given the "
            + "report excerpt and the current files, and its corrected change set is committed on top and verified")
    void aFailedBuildSendsItsFailuresBackToTheDevelopmentAgent() throws Exception {
        AgentState result = runner.run(runId, "Build a link shortener", RunKind.GREENFIELD);

        assertThat(developmentPrompts).hasSize(2);
        assertThat(developmentPrompts.get(0)).doesNotContain("FIX attempt", "BROKEN_VERSION");
        assertThat(developmentPrompts.get(1)).contains("FIX attempt", FAILING_TEST, "REPORT_EXCERPT_TEXT",
                "[INPUT kind=SELECTED_WORKSPACE_FILES", "[FILE path=app.py]", "BROKEN_VERSION");
        verify(records, times(2)).record(eq(runId), any());
        assertThat(latest.get().passed()).isTrue();
        // Two commits, the second on top of the first and holding the fixed file; the run goes on with that commit.
        Path workspace = temp.resolve("workspaces").resolve(runId.toString());
        assertThat(git(workspace, "rev-list", "--count", "HEAD")).isEqualTo("2");
        assertThat(git(workspace, "show", "HEAD:app.py")).contains("FIXED_VERSION");
        assertThat(result.<String>value("commit")).contains(git(workspace, "rev-parse", "HEAD"));
    }

    @Test
    @DisplayName("AC-AGT-65.2 each attempt stores a new artifact version, the next stages get the latest, and every "
            + "attempt's model call is reserved in the run's budget")
    void eachAttemptStoresANewVersionAndCountsInTheBudget() throws Exception {
        AgentState result = runner.run(runId, "Build a link shortener", RunKind.GREENFIELD);

        assertThat(stored).extracting(NewArtifact::role).containsExactly("development", "development");
        assertThat(result.<ArtifactRef>value("development")).get()
                .extracting(ArtifactRef::kind, ArtifactRef::version).containsExactly("development", 2);
        verify(budget, times(2)).reserve(eq(runId), eq("development"), anyLong(), anyInt());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String git(Path folder, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git failed: " + output);
        }
        return output;
    }
}
