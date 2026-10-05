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
import java.util.function.Predicate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import io.github.bereket1144.agentcontroller.workspace.PlatformRepository;
import io.github.bereket1144.agentcontroller.workspace.RunWorkspace;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceFiles;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/**
 * The development stage over the whole graph, with the real answer service, workspace (real git, in a temporary
 * platform repository) and stage wrapper. The model, the artifact store, the verification records and the container are
 * stand-ins: by default the first change set is broken and fails its test, the second is fixed and passes. A test may
 * script the answers of the model and the rule of the container before it runs the graph.
 */
class DevelopmentFixAttemptTest {

    private static final String FAILING_TEST = "tests.test_app.test_shorten";
    private static final String REPORT = "<testsuite>REPORT_EXCERPT_TEXT</testsuite>";
    private static final String BROKEN = "def shorten(url):\n    return None  # BROKEN_VERSION\n";
    private static final String FIXED = "def shorten(url):\n    return url[:5]  # FIXED_VERSION\n";
    private static final String PRODUCT = "apps/demo";

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
    private final AtomicInteger builds = new AtomicInteger();
    /** The changes that the stand-in model answers with, one list for each call of the development agent. */
    private List<List<Map<String, Object>>> script = List.of(
            List.of(write("app.py", BROKEN)), List.of(write("app.py", FIXED)));
    /** Tells whether the build of the product folder passes, for the stand-in container. */
    private Predicate<Path> buildPasses = product -> read(product.resolve("app.py")).contains("FIXED_VERSION");
    private Path platform;
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
            return new ModelResult(JsonMapper.shared().writeValueAsString(Map.of(
                    "changes", script.get(developmentCalls.getAndIncrement()),
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
        // The container is given the product folder and decides from the committed files whether the build passes.
        VerificationRunner container = (workspace, commitId, cancelled) -> {
            builds.incrementAndGet();
            boolean fixed = buildPasses.test(workspace);
            return fixed ? new VerificationResult(Outcome.PASSED, 1, 1, 0, List.of(), "", commitId)
                    : new VerificationResult(Outcome.FAILED, 1, 0, 1, List.of(FAILING_TEST), REPORT, commitId);
        };
        platform = PlatformRepository.create(temp.resolve("platform"));
        RunWorkspace workspaces = new RunWorkspace(temp.resolve("workspaces").toString(), platform.toString(), "HEAD",
                PRODUCT);
        DevelopmentFinish finish = new DevelopmentFinish(store, workspaces, container, records, new WorkspaceFiles(),
                mock(io.github.bereket1144.agentcontroller.run.StopCheck.class));
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
        // Two commits on the run's branch, the second on top of the first and holding the fixed file; the run goes on
        // with that commit.
        Path workspace = temp.resolve("workspaces").resolve(runId.toString());
        assertThat(git(workspace, "rev-list", "--count", git(platform, "rev-parse", "main") + "..HEAD")).isEqualTo("2");
        assertThat(git(workspace, "show", "HEAD:" + PRODUCT + "/app.py")).contains("FIXED_VERSION");
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

    @Test
    @DisplayName("AC-AGT-69.3 the task of a fix attempt explains deleting a file, and renaming as a write and a delete")
    void theFixTaskExplainsDeleting() {
        runner.run(runId, "Build a task tracker", RunKind.GREENFIELD);

        assertThat(developmentPrompts.get(1)).contains("{\"path\": \"...\", \"delete\": true}",
                "write it under the new name and delete the old one");
    }

    @Test
    @DisplayName("AC-AGT-69.4 a fix attempt that changes nothing runs no build and fails the attempt, the stage is "
            + "attempted again, and the next attempt is told that its previous answer changed nothing")
    void anUnchangedFixAttemptRunsNoBuild() {
        // The second answer repeats the broken file; the third is the repair.
        script = List.of(List.of(write("app.py", BROKEN)), List.of(write("app.py", BROKEN)),
                List.of(write("app.py", FIXED)));
        Logger logger = (Logger) LoggerFactory.getLogger(DevelopmentFinish.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.list = Collections.synchronizedList(new ArrayList<>());
        appender.start();
        logger.addAppender(appender);

        AgentState result;
        try {
            result = runner.run(runId, "Build a task tracker", RunKind.GREENFIELD);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        // Three answers, but only two builds: the unchanged answer was not built, and it was not recorded.
        assertThat(developmentPrompts).hasSize(3);
        assertThat(builds).hasValue(2);
        verify(records, times(2)).record(eq(runId), any());
        assertThat(result.<String>value("verification")).contains("PASSED");
        // The note comes with the attempt right after the unchanged one, and with no other.
        String note = "Your previous answer changed nothing. At least one file must change or be deleted.";
        assertThat(developmentPrompts.get(1)).doesNotContain(note);
        assertThat(developmentPrompts.get(2)).contains("FIX attempt", note);
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage))
                .contains("event=fix_attempt_unchanged runId=" + runId + " stage=development attempt=2");
    }

    @Test
    @DisplayName("AC-AGT-69.5 a module named like a standard library module is repaired by one change set that writes "
            + "it under a new name, rewrites the import and deletes the old file, and the second build passes")
    void aBadlyNamedModuleIsRenamedByAFixAttempt() throws Exception {
        script = List.of(
                List.of(write("http.py", "def get():\n    return 1\n"),
                        write("test_http.py", "import http\n\n\ndef test_get():\n    assert http.get() == 1\n")),
                List.of(write("http_api.py", "def get():\n    return 1\n"),
                        write("test_http.py",
                                "import http_api\n\n\ndef test_get():\n    assert http_api.get() == 1\n"),
                        deletion("http.py")));
        // As in practice, the tests cannot run while a file hides the standard library module of the same name.
        buildPasses = product -> !Files.exists(product.resolve("http.py"));

        AgentState result = runner.run(runId, "Build a task tracker", RunKind.GREENFIELD);

        Path workspace = temp.resolve("workspaces").resolve(runId.toString());
        assertThat(builds).hasValue(2);
        assertThat(latest.get().passed()).isTrue();
        assertThat(git(workspace, "diff", "--no-renames", "--name-status", "HEAD~1", "HEAD").lines())
                .containsExactlyInAnyOrder("A\t" + PRODUCT + "/http_api.py", "M\t" + PRODUCT + "/test_http.py",
                        "D\t" + PRODUCT + "/http.py");
        assertThat(result.<String>value("commit")).contains(git(workspace, "rev-parse", "HEAD"));
    }

    private static Map<String, Object> write(String path, String content) {
        return Map.of("path", path, "content", content);
    }

    private static Map<String, Object> deletion(String path) {
        return Map.of("path", path, "delete", true);
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
