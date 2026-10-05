package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.ModelUnavailableException;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.OutputInvalidException;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;

/** Run explicitly with -Dagent.model.live-check=true; ordinary verify never contacts a model. */
class RequirementsLiveCheckTest {

    private static final String CLEAR_REQUIREMENT = "Create a small Python command-line calculator that adds, "
            + "subtracts, multiplies and divides two numbers and reports an error for division by zero. "
            + "Include tests.";

    @Test
    @EnabledIfSystemProperty(named = "agent.model.live-check", matches = "true")
    @DisplayName("AC-AGT-12.6 a clear requirement gives a valid requirements answer or a typed failure")
    void clearRequirementGivesAValidAnswerOrATypedFailure() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ModelProfiles.class, RoleCatalog.class, OllamaModelClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RoleCatalog roles = context.getBean(RoleCatalog.class);
                    ModelProfiles profiles = context.getBean(ModelProfiles.class);
                    OutputValidator validator = new OutputValidator(roles,
                            List.of(new RequirementsStoryIdsCheck(), new RequirementsCriteriaCheck()));
                    StructuredAnswerService service = new StructuredAnswerService(
                            context.getBean(OllamaModelClient.class), profiles, new PromptBuilder(profiles, roles, 3),
                            validator, mock(ModelCallRecords.class), mock(ModelCallBudget.class), 2,
                            Duration.ofMillis(200));

                    try {
                        JsonNode answer = service.call(UUID.randomUUID(), "requirements", "Write the stories.",
                                List.of(new PromptInput(InputKind.ORIGINAL_REQUIREMENT, "1", CLEAR_REQUIREMENT,
                                        null, false))).value();
                        assertThat(answer.get("stories").isArray()).isTrue();
                        assertThat(answer.get("openQuestions").isArray()).isTrue();
                        assertThat(answer.get("assumptions").isArray()).isTrue();
                    } catch (OutputInvalidException | ModelUnavailableException typedFailure) {
                        assertThat(typedFailure.getMessage()).isNotBlank();
                    }
                });
    }
}
