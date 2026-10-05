package io.github.bereket1144.agentcontroller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.NewArtifact;
import io.github.bereket1144.agentcontroller.model.ModelCallBudget;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.ModelClient.ModelResult;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.model.ModelProfiles;
import io.github.bereket1144.agentcontroller.model.OutputValidator;
import io.github.bereket1144.agentcontroller.model.PromptBuilder;
import io.github.bereket1144.agentcontroller.model.RequirementsCriteriaCheck;
import io.github.bereket1144.agentcontroller.model.RequirementsStoryIdsCheck;
import io.github.bereket1144.agentcontroller.model.ScriptedModelClient;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import io.github.bereket1144.agentcontroller.run.RunKind;
import io.github.bereket1144.agentcontroller.run.ClarificationService;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** The requirements node with the real answer service and a scripted model; only the artifact store is a mock. */
class RequirementsAgentNodeTest {

    private static final String REQUIREMENT = "PRIVATE_REQUIREMENT build a small command-line calculator";
    private static final String ANSWER = """
            {"stories":[{"id":"S-1","title":"Add","role":"user","want":"to add two numbers","benefit":"a sum",
              "acceptanceCriteria":[{"id":"S-1.1","text":"PRIVATE_CRITERION two numbers are added"}]}],
             "openQuestions":[],"assumptions":[]}
            """;

    private final ModelProfile profile = new ModelProfile("local", "model", URI.create("http://localhost:11434"),
            8192, 512, Duration.ofSeconds(2), 1, 2, 0, null);
    private final ScriptedModelClient model = new ScriptedModelClient();
    private final ArtifactStore store = mock(ArtifactStore.class);
    private final ArtifactRef ref = new ArtifactRef("requirements", 1, "f".repeat(64));
    private final UUID runId = UUID.randomUUID();

    @Test
    @DisplayName("AC-AGT-12.1 the node sends the run's requirement to the requirements role and stores the answer")
    void storesTheAnswerAsARequirementsArtifact() throws IOException {
        RequirementsAgentNode node = node();

        Map<String, Object> update = node.prepare(state());

        assertThat(model.calls()).hasSize(2).allSatisfy(call -> {
            assertThat(call.role()).isEqualTo("requirements");
            assertThat(call.userText()).contains(REQUIREMENT);
        });
        ArgumentCaptor<NewArtifact> stored = ArgumentCaptor.forClass(NewArtifact.class);
        verify(store).store(stored.capture());
        assertThat(stored.getValue().runId()).isEqualTo(runId);
        assertThat(stored.getValue().role()).isEqualTo("requirements");
        assertThat(stored.getValue().content().get("stories").get(0).get("id").asString()).isEqualTo("S-1");
        assertThat(stored.getValue().summary()).isEqualTo("1 stories, 0 open questions, 0 assumptions");
        assertThat(stored.getValue().profile()).isEqualTo(profile);
        assertThat(update).containsExactly(Map.entry("requirements", ref));
    }

    @Test
    @DisplayName("AC-AGT-12.3 with the agent enabled, the next stage receives the artifact reference")
    void theNextStageReceivesTheReference() throws IOException, GraphStateException {
        WorkflowRunner runner = new WorkflowRunner(new DummySdlcNodes(), Optional.of(node()));

        AgentState result = runner.run(runId, REQUIREMENT, RunKind.GREENFIELD);

        assertThat(result.<Object>value("requirements")).contains(ref);
        assertThat(result.<Object>value("design")).contains("design:done");
    }

    @Test
    @DisplayName("AC-AGT-12.3 the agent node exists only when its setting says agent, and the dummy is the default")
    void theSettingSwitchesTheNode() {
        ApplicationContextRunner contexts = new ApplicationContextRunner()
                .withUserConfiguration(RequirementsAgentNode.class)
                .withBean(StructuredAnswerService.class, () -> mock(StructuredAnswerService.class))
                .withBean(ArtifactStore.class, () -> mock(ArtifactStore.class))
                .withBean(ClarificationService.class, () -> mock(ClarificationService.class));

        contexts.run(context -> assertThat(context).doesNotHaveBean(RequirementsAgentNode.class));
        contexts.withPropertyValues("agent.stages.requirements=agent")
                .run(context -> assertThat(context).hasSingleBean(RequirementsAgentNode.class));
    }

    @Test
    @DisplayName("AC-AGT-12.5 the node logs ids and counts and never the requirement or the answer text")
    void logsNoText() throws IOException {
        RequirementsAgentNode node = node();
        // The root logger, so that a line from any logger on the way would be caught, not only the node's.
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            node.prepare(state());
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("PRIVATE_"));
        assertThat(appender.list)
                .filteredOn(event -> event.getLoggerName().equals(RequirementsAgentNode.class.getName()))
                .singleElement().satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage()).contains("event=requirements_artifact_stored",
                            "runId=" + runId, "role=requirements", "kind=requirements", "version=1",
                            "fingerprint=" + ref.fingerprint(), "stories=1", "openQuestions=0", "assumptions=0");
                });
    }

    private AgentState state() {
        return new AgentState(Map.of("runId", runId, "requirement", REQUIREMENT, "kind", RunKind.GREENFIELD));
    }

    private RequirementsAgentNode node() throws IOException {
        model.addResult(new ModelResult("{\"questions\":[],\"assumptions\":[]}", FinishReason.COMPLETE, null, null, null));
        model.addResult(new ModelResult(ANSWER, FinishReason.COMPLETE, null, null, null));
        when(store.store(any())).thenReturn(ref);
        RoleCatalog roles = new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties"));
        ModelProfiles profiles = mock(ModelProfiles.class);
        when(profiles.forRole("requirements")).thenReturn(profile);
        when(profiles.safetyMarginPercent()).thenReturn(10);
        OutputValidator validator = new OutputValidator(roles,
                List.of(new RequirementsStoryIdsCheck(), new RequirementsCriteriaCheck()));
        StructuredAnswerService answers = new StructuredAnswerService(model, profiles,
                new PromptBuilder(profiles, roles, 3), validator, mock(ModelCallRecords.class),
                mock(ModelCallBudget.class), 2, Duration.ZERO);
        ClarificationService clarification = mock(ClarificationService.class);
        when(clarification.questions(runId)).thenReturn(new ClarificationService.Questions(List.of()));
        return new RequirementsAgentNode(answers, store, clarification);
    }
}
