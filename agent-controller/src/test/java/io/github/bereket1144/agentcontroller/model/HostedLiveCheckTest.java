package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Run explicitly with -Dagent.model.hosted-live-check=true; ordinary verify never contacts a hosted model. The
 * hosted profile and its key come from the environment, for example AGENT_MODELS_PRIMARY=hosted,
 * AGENT_MODELS_PROFILES_HOSTED_MODEL, _BASE_ADDRESS, _CONTEXT_WINDOW, _ANSWER_LIMIT, _CALL_TIMEOUT and _API_KEY.
 * It asserts structure only, never wording.
 */
class HostedLiveCheckTest {

    @Test
    @EnabledIfSystemProperty(named = "agent.model.hosted-live-check", matches = "true")
    @DisplayName("AC-AGT-62.1 an opt-in check sends one small request to the hosted model and receives an answer")
    void hostedModelAnswersWhenExplicitlyEnabled() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ModelProfiles.class, RoleCatalog.class, HostedModelClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelProfile profile = context.getBean(ModelProfiles.class).primary();
                    assertThat(profile.apiKey()).as("the primary profile must be the keyed hosted profile").isNotBlank();
                    ModelClient.ModelResult result = context.getBean(HostedModelClient.class)
                            .call("requirements", profile, "Answer briefly.", "Say hello.");
                    assertThat(result.text()).isNotBlank();
                    assertThat(result.finishReason()).isNotNull();
                });
    }
}
