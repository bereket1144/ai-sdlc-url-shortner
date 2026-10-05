package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.NewArtifact;
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
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * The generic agent stage node and its setting, with the real answer service, prompt builder and validator and a
 * scripted model. Only the artifact store and the stage inputs are mocks.
 */
class AgentStageNodeTest {

    private static final String REQUIREMENT = "PRIVATE_REQUIREMENT build a small command-line calculator";
    private static final ArtifactRef STORED = new ArtifactRef("stored", 1, "f".repeat(64));
    /** A valid answer for each role that runs in these tests, with every required field of its role file. */
    private static final Map<String, String> ANSWERS = Map.of(
            "impactAnalysis", """
                    {"affectedFiles":[],"affectedBehaviors":[],"dependencies":[],"dataFlows":[],"risks":[],
                     "evidence":[],"openQuestions":[{"id":"Q-1","text":"Which output format?"},"Who may delete?"]}""",
            "design", """
                    {"components":[],"interfaces":[],"dataFlow":"input to output","decisions":[],"alternatives":[],
                     "risks":[],"diagrams":[]}""",
            "planning", """
                    {"tasks":[]}""",
            "development", """
                    {"changes":[],"rationale":"why","affectedCriteria":[],"errorHandling":"how",
                     "verificationNotes":[]}""",
            "qa", """
                    {"proposedTests":[],"criterionMapping":{},"observedEvidence":[],"coverageGaps":[]}""",
            "review", """
                    {"reviewedFiles":[],"findings":[],"evidence":[],"resolutionStatus":"open"}""",
            "documentation", """
                    {"setup":"s","usage":"u","architecture":"a","examples":[],"knownLimits":[],"evidenceLinks":[]}""");

    private final ModelProfile profile = new ModelProfile("local", "model", URI.create("http://localhost:11434"),
            8192, 512, Duration.ofSeconds(2), 1, 2, 0, null);
    private final ArtifactStore store = mock(ArtifactStore.class);
    private final StageInputs stageInputs = mock(StageInputs.class);
    private final UUID runId = UUID.randomUUID();
    private RoleCatalog roles;

    @BeforeEach
    void setUp() throws IOException {
        roles = new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties"));
        // Whatever is stored gets a reference of its role's kind; nothing is read from the database.
        when(store.store(any())).thenAnswer(call -> {
            NewArtifact artifact = call.getArgument(0);
            return new ArtifactRef(roles.get(artifact.role()).output().kind(), 1, STORED.fingerprint());
        });
        when(stageInputs.resolve(any(), any(), any()))
                .thenAnswer(call -> new Resolved(List.of(), call.getArgument(2)));
    }

    @Test
    @DisplayName("AC-AGT-62.2 the setting switches each role's stage to the agent node, and the dummy is the default")
    void theSettingSwitchesTheNodePerRole() {
        ApplicationContextRunner contexts = new ApplicationContextRunner()
                .withUserConfiguration(AgentStages.class)
                .withBean(RoleCatalog.class, () -> roles)
                .withBean(StageInputs.class, () -> stageInputs)
                .withBean(StructuredAnswerService.class, () -> mock(StructuredAnswerService.class))
                .withBean(ArtifactStore.class, () -> store);

        contexts.run(context -> assertThat(context.getBean(AgentStages.class).nodes()).isEmpty());
        contexts.withPropertyValues("agent.stages.design=agent", "agent.stages.impactAnalysis=agent",
                        "agent.stages.qa=dummy")
                .run(context -> assertThat(context.getBean(AgentStages.class).nodes())
                        .containsOnlyKeys("design", "impactAnalysis"));
        contexts.withPropertyValues("agent.stages.impactAnalysis=agent", "agent.stages.design=agent",
                        "agent.stages.planning=agent", "agent.stages.development=agent", "agent.stages.qa=agent",
                        "agent.stages.review=agent", "agent.stages.documentation=agent")
                .run(context -> assertThat(context.getBean(AgentStages.class).nodes())
                        .containsOnlyKeys(ANSWERS.keySet()));
    }

    @Test
    @DisplayName("AC-AGT-62.3 an agent stage reads only artifacts its role may read, stores one artifact and passes on only its reference")
    void readsAllowedArtifactsStoresOneAndPassesOnTheReference() {
        ArtifactRef requirements = new ArtifactRef("requirements", 1, "a".repeat(64));
        // The design role does not list the development kind, so this reference must not be handed to it.
        ArtifactRef development = new ArtifactRef("development", 1, "b".repeat(64));
        List<String> userTexts = Collections.synchronizedList(new ArrayList<>());
        when(stageInputs.resolve(runId, "design", List.of(requirements))).thenReturn(new Resolved(
                List.of(new PromptInput(InputKind.REQUIREMENTS_ARTIFACT, "1", "REQUIREMENTS_ARTIFACT_TEXT", "short",
                        false)), List.of(requirements)));
        AgentStageNode node = new AgentStageNode("design", roles, stageInputs, service(scripted(userTexts)), store);
        // "impactAnalysis" is a dummy marker text, not a reference, so there is nothing of it to read.
        AgentState state = new AgentState(Map.of("runId", runId, "requirement", REQUIREMENT,
                "kind", RunKind.GREENFIELD, "requirements", requirements, "development", development,
                "impactAnalysis", "impactAnalysis:done"));

        Map<String, Object> update = node.execute(state);

        verify(stageInputs).resolve(runId, "design", List.of(requirements));
        assertThat(userTexts).singleElement().asString().contains("REQUIREMENTS_ARTIFACT_TEXT", REQUIREMENT);
        ArgumentCaptor<NewArtifact> stored = ArgumentCaptor.forClass(NewArtifact.class);
        verify(store).store(stored.capture());
        assertThat(stored.getValue().role()).isEqualTo("design");
        assertThat(stored.getValue().consumed()).containsExactly(requirements);
        assertThat(stored.getValue().profile()).isEqualTo(profile);
        assertThat(update).containsExactly(Map.entry("design", new ArtifactRef("design", 1, STORED.fingerprint())));
    }

    @Test
    @DisplayName("AC-AGT-62.6 the development prompt carries the file layout from the configuration, and the platform code words none")
    void theDevelopmentPromptCarriesTheConfiguredLayout() {
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        ModelClient model = (role, modelProfile, system, user) -> {
            prompts.add(system + "\n" + user);
            return new ModelResult(ANSWERS.get(role), FinishReason.COMPLETE, null, null, null);
        };
        MockEnvironment environment = new MockEnvironment().withProperty("agent.stages.development", "agent")
                .withProperty("sandbox.verify.file-layout", "Put every file in the src folder: CONFIGURED_LAYOUT_TEXT.");
        AgentStageNode node = new AgentStages(environment, roles, stageInputs, service(model), store)
                .nodes().get("development");

        node.execute(new AgentState(Map.of("runId", runId, "requirement", REQUIREMENT)));

        // The task text and the role text are both in the prompt, so one check covers both.
        assertThat(prompts).singleElement().asString()
                .contains("CONFIGURED_LAYOUT_TEXT", "complete file")
                .doesNotContain("flat layout", "tests/", "url-shortener", "apps/", "runs/");
    }

    @Test
    @DisplayName("AC-AGT-49.1 the impact analysis, design, planning and development agents of a change run get the "
            + "existing files before their other inputs, and QA, review and documentation get none")
    void theExistingFilesComeFirstForTheFourRolesOnly() {
        ExistingFiles existing = mock(ExistingFiles.class);
        when(existing.forRun(any())).thenReturn(Optional.of(
                new PromptInput(InputKind.SELECTED_WORKSPACE_FILES, "c1", "EXISTING_FILE_TEXT", "short", true)));
        Map<String, String> prompts = new HashMap<>();
        ModelClient model = (role, modelProfile, system, user) -> {
            prompts.put(role, user);
            return new ModelResult(ANSWERS.get(role), FinishReason.COMPLETE, null, null, null);
        };
        Map<String, AgentStageNode> nodes = stages(model, Optional.of(existing), "impactAnalysis", "design", "planning",
                "development", "qa", "review", "documentation").nodes();
        AgentState state = new AgentState(Map.of("runId", runId, "requirement", REQUIREMENT,
                "kind", RunKind.BROWNFIELD));

        for (String role : List.of("impactAnalysis", "design", "planning", "development", "qa", "review",
                "documentation")) {
            nodes.get(role).execute(state);
        }

        // These three roles also read the requirement text, which the files must come before.
        for (String role : List.of("impactAnalysis", "design", "planning")) {
            assertThat(prompts.get(role)).contains("EXISTING_FILE_TEXT");
            assertThat(prompts.get(role).indexOf("kind=SELECTED_WORKSPACE_FILES"))
                    .isLessThan(prompts.get(role).indexOf("kind=ORIGINAL_REQUIREMENT"));
        }
        assertThat(prompts.get("development")).contains("EXISTING_FILE_TEXT");
        for (String role : List.of("qa", "review", "documentation")) {
            assertThat(prompts.get(role)).doesNotContain("EXISTING_FILE_TEXT");
        }
        verify(existing, times(4)).forRun(state);
    }

    @Test
    @DisplayName("AC-AGT-49.1 an attempt that brings the current files itself is not given the existing files twice")
    void aFixAttemptIsNotGivenTheFilesTwice() {
        ExistingFiles existing = mock(ExistingFiles.class);
        ModelClient model = (role, modelProfile, system, user) ->
                new ModelResult(ANSWERS.get(role), FinishReason.COMPLETE, null, null, null);
        AgentStageNode node = stages(model, Optional.of(existing), "development").nodes().get("development");
        PromptInput current = new PromptInput(InputKind.SELECTED_WORKSPACE_FILES, "c2", "CURRENT", "short", true);

        node.execute(new AgentState(Map.of("runId", runId, "requirement", REQUIREMENT, "kind", RunKind.BROWNFIELD)),
                "FIX attempt", List.of(current));

        verify(existing, never()).forRun(any());
    }

    @Test
    @DisplayName("AC-AGT-62.4 open questions stay in the artifact, each is also listed as an assumption, and the run goes on")
    void openQuestionsBecomeAssumptionsAndTheRunGoesOn() throws GraphStateException {
        AgentStages stages = stages(scripted(new ArrayList<>()), "impactAnalysis");
        WorkflowRunner runner = new WorkflowRunner(new DummySdlcNodes(), Optional.empty(), Optional.of(stages));

        AgentState result = runner.run(runId, REQUIREMENT, RunKind.BROWNFIELD);

        ArgumentCaptor<NewArtifact> stored = ArgumentCaptor.forClass(NewArtifact.class);
        verify(store).store(stored.capture());
        JsonNode content = stored.getValue().content();
        assertThat(content.get("openQuestions").size()).isEqualTo(2);
        assertThat(content.get("assumptions").size()).isEqualTo(2);
        assertThat(content.get("assumptions").get(0).asString()).isEqualTo("Unanswered question: Which output format?");
        assertThat(content.get("assumptions").get(1).asString()).isEqualTo("Unanswered question: Who may delete?");
        // The run was not paused: the stages after impact analysis ran and the branches joined.
        assertThat(result.<Object>value("impactAnalysis")).containsInstanceOf(ArtifactRef.class);
        assertThat(result.<Object>value("design")).contains("design:done");
        assertThat(result.<Boolean>value("joined")).contains(true);
    }

    @Test
    @DisplayName("AC-AGT-62.5 with every agent stage enabled the order and the parallel join of FLOW-1 are unchanged")
    void theOrderAndTheParallelJoinAreUnchanged() throws GraphStateException {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch branchesStarted = new CountDownLatch(3);
        ModelClient model = (role, modelProfile, system, user) -> {
            order.add(role);
            if (List.of("qa", "review", "documentation").contains(role)) {
                // Each branch waits until all three have started, so a sequential run cannot get past this.
                branchesStarted.countDown();
                awaitAllBranches(branchesStarted);
            }
            return new ModelResult(ANSWERS.get(role), FinishReason.COMPLETE, null, null, null);
        };
        AgentStages stages = stages(model, "impactAnalysis", "design", "planning", "development", "qa", "review",
                "documentation");
        WorkflowRunner runner = new WorkflowRunner(new DummySdlcNodes(), Optional.empty(), Optional.of(stages));

        AgentState result = runner.run(runId, REQUIREMENT, RunKind.GREENFIELD);

        assertThat(order).hasSize(6);
        assertThat(order.subList(0, 3)).containsExactly("design", "planning", "development");
        assertThat(order.subList(3, 6)).containsExactlyInAnyOrder("qa", "review", "documentation");
        for (String role : List.of("design", "planning", "development", "qa", "review", "documentation")) {
            assertThat(result.<Object>value(role)).containsInstanceOf(ArtifactRef.class);
        }
        assertThat(result.<Boolean>value("joined")).contains(true);
    }

    /** Answers every call with the valid answer of the called role and notes the prompt it was given. */
    private static ModelClient scripted(List<String> userTexts) {
        return (role, modelProfile, system, user) -> {
            userTexts.add(user);
            return new ModelResult(ANSWERS.get(role), FinishReason.COMPLETE, null, null, null);
        };
    }

    private AgentStages stages(ModelClient model, String... agentRoles) {
        return stages(model, Optional.empty(), agentRoles);
    }

    private AgentStages stages(ModelClient model, Optional<ExistingFiles> existingFiles, String... agentRoles) {
        MockEnvironment environment = new MockEnvironment();
        for (String role : agentRoles) {
            environment.setProperty("agent.stages." + role, "agent");
        }
        return new AgentStages(environment, roles, stageInputs, service(model), store, existingFiles);
    }

    private StructuredAnswerService service(ModelClient model) {
        ModelProfiles profiles = mock(ModelProfiles.class);
        when(profiles.forRole(anyString())).thenReturn(profile);
        when(profiles.safetyMarginPercent()).thenReturn(10);
        return new StructuredAnswerService(model, profiles, new PromptBuilder(profiles, roles, 3),
                new OutputValidator(roles, List.of()), mock(ModelCallRecords.class), mock(ModelCallBudget.class), 2,
                Duration.ZERO);
    }

    private static void awaitAllBranches(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("QA, review and documentation did not run side by side");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the other branches", interrupted);
        }
    }
}
