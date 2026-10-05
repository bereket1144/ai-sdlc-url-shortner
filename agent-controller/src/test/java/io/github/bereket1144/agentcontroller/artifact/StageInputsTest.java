package io.github.bereket1144.agentcontroller.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.RoleDefinition;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.NewArtifact;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.artifact.StageInputs.Resolved;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.model.ModelProfiles;
import io.github.bereket1144.agentcontroller.model.PromptBuilder;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.Prompt;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.run.Run;
import io.github.bereket1144.agentcontroller.run.RunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Flyway creates the real PostgreSQL schema; Hibernate only validates the mapping.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, ArtifactStore.class, RoleCatalog.class, StageInputs.class})
class StageInputsTest {

    private static final ModelProfile PROFILE = new ModelProfile("local", "qwen-test",
            URI.create("http://localhost:11434"), 32768, 4096, Duration.ofSeconds(300), 1, 2, 0.0, null);

    private final JsonMapper mapper = JsonMapper.shared();

    @Autowired
    private StageInputs stageInputs;

    @Autowired
    private ArtifactStore store;

    @Autowired
    private RoleCatalog roles;

    @Autowired
    private RunRepository runRepository;

    @Test
    @DisplayName("AC-AGT-16.1 the prompt inputs are exactly the handed versions, so the approved version and not the latest")
    void usesTheHandedVersionAndNothingElse() {
        UUID runId = newRun();
        ArtifactRef approved = store.store(artifact(runId, "requirements", "{\"v\":\"approved\"}"));
        store.store(artifact(runId, "requirements", "{\"v\":\"newer, not approved\"}"));
        store.store(artifact(runId, "design", "{\"v\":\"design the role was not handed\"}"));

        Resolved resolved = stageInputs.resolve(runId, "development", List.of(approved));

        assertThat(resolved.inputs()).singleElement().satisfies(input -> {
            assertThat(input.kind()).isEqualTo(InputKind.REQUIREMENTS_ARTIFACT);
            assertThat(input.version()).isEqualTo("1");
            assertThat(input.fullText()).isEqualTo("{\"v\":\"approved\"}");
            assertThat(input.shortText()).isEqualTo("summary");
        });
    }

    @Test
    @DisplayName("AC-AGT-16.3 a stored artifact records exactly the versions that went into its prompt")
    void recordsExactlyTheVersionsThatWentIntoThePrompt() {
        UUID runId = newRun();
        ArtifactRef requirements = store.store(artifact(runId, "requirements", "{\"v\":1}"));
        store.store(artifact(runId, "requirements", "{\"v\":2}"));
        ArtifactRef plan = store.store(artifact(runId, "planning", "{\"v\":1}"));

        Resolved resolved = stageInputs.resolve(runId, "development", List.of(requirements, plan));
        ArtifactRef developed = store.store(new NewArtifact(runId, "development", json("{\"done\":true}"),
                "summary", PROFILE, resolved.consumed()));

        assertThat(resolved.inputs()).extracting(input -> input.kind()).containsExactly(
                InputKind.REQUIREMENTS_ARTIFACT, InputKind.PLAN_ARTIFACT);
        assertThat(store.find(runId, "development", developed.version()).orElseThrow().consumed())
                .containsExactly(requirements, plan);
    }

    @Test
    @DisplayName("STEP-104.1, 104.3 the documentation prompt holds the plan and the paths and rationale of the development work, and no file content")
    void documentationGetsThePlanAndThePathsButNoFileContents() {
        UUID runId = newRun();
        ArtifactRef plan = store.store(artifact(runId, "planning", "{\"tasks\":[\"PLAN_TASK_TEXT\"]}"));
        ArtifactRef development = store.store(artifact(runId, "development", """
                {"changes":[{"path":"src/app.py","content":"FILE_BODY_ONE"},
                            {"path":"docs/notes.md","content":"FILE_BODY_TWO"}],
                 "rationale":"RATIONALE_TEXT"}"""));
        ModelProfiles profiles = mock(ModelProfiles.class);
        when(profiles.safetyMarginPercent()).thenReturn(10);

        Resolved resolved = stageInputs.resolve(runId, "documentation", List.of(plan, development));
        Prompt prompt = new PromptBuilder(profiles, roles, 3).build("documentation", PROFILE, "Document the run.",
                resolved.inputs());

        assertThat(resolved.inputs()).extracting(PromptInput::kind)
                .containsExactly(InputKind.PLAN_ARTIFACT, InputKind.DEVELOPMENT_OUTLINE);
        assertThat(prompt.userText()).contains("PLAN_TASK_TEXT", "src/app.py", "docs/notes.md", "RATIONALE_TEXT")
                .doesNotContain("FILE_BODY_ONE", "FILE_BODY_TWO");
    }

    @Test
    @DisplayName("AC-AGT-16.4 an answer from a role can only create an artifact of that role's own kind")
    void aRoleCanOnlyCreateItsOwnKind() {
        UUID runId = newRun();
        RoleDefinition before = roles.get("design");

        // The answer names another kind and a decision; the kind still comes from the role, never from the answer.
        store.store(artifact(runId, "design",
                "{\"kind\":\"requirements\",\"decision\":\"approved\",\"role\":\"requirements\"}"));

        assertThat(store.forRun(runId)).extracting(StoredArtifact::kind).containsExactly("design");
        assertThat(store.find(runId, "requirements", 1)).isEmpty();
        assertThat(roles.get("design")).isEqualTo(before);
        assertThatThrownBy(() -> roles.all().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private UUID newRun() {
        return runRepository.saveAndFlush(new Run("Build a task tracker")).getId();
    }

    private NewArtifact artifact(UUID runId, String role, String contentJson) {
        return new NewArtifact(runId, role, json(contentJson), "summary", PROFILE, List.of());
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }
}
