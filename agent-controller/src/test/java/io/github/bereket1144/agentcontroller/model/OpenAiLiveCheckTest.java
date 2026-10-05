package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Run explicitly with -Dagent.model.openai-live-check=true; ordinary verify never contacts a model. The profile and
 * its key come from the environment, for example AGENT_MODELS_PRIMARY=router, AGENT_MODELS_PROFILES_ROUTER_MODEL,
 * _BASE_ADDRESS (https://openrouter.ai/api/v1), _API_STYLE (openai), _CONTEXT_WINDOW, _ANSWER_LIMIT, _CALL_TIMEOUT and
 * _API_KEY. It asserts structure only, never wording.
 */
class OpenAiLiveCheckTest {

    @Test
    @EnabledIfSystemProperty(named = "agent.model.openai-live-check", matches = "true")
    @DisplayName("AC-AGT-63.7 an opt-in check sends one small request through an OpenAI-style API and receives an answer")
    void modelAnswersWhenExplicitlyEnabled() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ModelProfiles.class, RoleCatalog.class, OpenAiCompatibleModelClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelProfile profile = context.getBean(ModelProfiles.class).primary();
                    assertThat(profile.style()).isEqualTo(ModelProfile.ApiStyle.OPENAI);
                    ModelClient.ModelResult result = context.getBean(OpenAiCompatibleModelClient.class)
                            .call("requirements", profile, "Answer briefly.", "Say hello.");
                    assertThat(result.text()).isNotBlank();
                    assertThat(result.finishReason()).isNotNull();
                });
    }
}
