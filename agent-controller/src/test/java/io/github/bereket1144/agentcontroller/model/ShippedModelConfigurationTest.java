package io.github.bereket1144.agentcontroller.model;

import static io.github.bereket1144.agentcontroller.model.ModelConfigFixture.environmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

// Reads the application.properties that ships in src/main/resources. Nothing from the machine's own environment
// is allowed in, so that the test is about the file and not about the shell it runs in.
class ShippedModelConfigurationTest {

    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(context -> {
                MutablePropertySources sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ModelProfiles.class);

    @Test
    @DisplayName("AC-AGT-2.2 the shipped configuration holds one local profile with a 32,768-token window, no key, and it is the primary")
    void shippedConfigurationHoldsTheLocalTrialModel() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ModelProperties.class).profiles()).containsOnlyKeys("local");
            ModelProfiles profiles = context.getBean(ModelProfiles.class);
            assertThat(profiles.primary())
                    .returns("local", ModelProfile::name)
                    .returns(32768, ModelProfile::contextWindow)
                    .returns(null, ModelProfile::apiKey);
            assertThat(profiles.fallback()).isEmpty();
        });
    }

    @Test
    @DisplayName("AC-AGT-62.6 the shipped configuration holds the file layout of the development prompt: a flat layout with one tests folder, naming no product or run folder")
    void shippedConfigurationHoldsTheFileLayout() {
        shipped.run(context -> assertThat(context.getEnvironment().getProperty("sandbox.verify.file-layout"))
                .contains("flat layout", "tests/")
                .doesNotContain("url-shortener", "apps/", "runs/"));
    }

    @Test
    @DisplayName("AC-AGT-2.2 an environment variable replaces the model identifier of the shipped local profile")
    void environmentVariableReplacesTheShippedModelIdentifier() {
        shipped.withInitializer(environmentVariable("AGENT_MODELS_PROFILES_LOCAL_MODEL", "another-model"))
                .run(context -> assertThat(context.getBean(ModelProfiles.class).primary().model())
                        .isEqualTo("another-model"));
    }
}
