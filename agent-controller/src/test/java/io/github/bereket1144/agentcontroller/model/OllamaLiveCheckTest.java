package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Run explicitly with -Dagent.model.live-check=true; ordinary verify never contacts Ollama. */
class OllamaLiveCheckTest {

    @Test
    @EnabledIfSystemProperty(named = "agent.model.live-check", matches = "true")
    @DisplayName("AC-AGT-3.8 an opt-in check calls the configured local model and receives an answer")
    void localModelAnswersWhenExplicitlyEnabled() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ModelProfiles.class, RoleCatalog.class, OllamaModelClient.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ModelProfile profile = context.getBean(ModelProfiles.class).primary();
                    ModelClient.ModelResult result = context.getBean(OllamaModelClient.class)
                            .call("requirements", profile, "Answer briefly.", "Say hello.");
                    assertThat(result.text()).isNotBlank();
                });
    }
}
