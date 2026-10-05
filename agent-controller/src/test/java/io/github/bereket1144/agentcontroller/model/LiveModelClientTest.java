package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiveModelClientTest {

    @Test
    @DisplayName("AC-AGT-62.1, AC-AGT-63.1 a profile is called with the client of its API style: Ollama without a key, Anthropic with one, OpenAI when named")
    void sendsEachCallToTheClientOfItsProfile() {
        OllamaModelClient local = mock(OllamaModelClient.class);
        HostedModelClient hosted = mock(HostedModelClient.class);
        OpenAiCompatibleModelClient openAi = mock(OpenAiCompatibleModelClient.class);
        ModelProfile unkeyed = profile("local", null);
        ModelProfile keyed = profile("hosted", "key");
        ModelProfile openAiStyle = new ModelProfile("router", "router-model", URI.create("http://localhost"), 32768,
                1024, Duration.ofSeconds(2), 1, 2, 0.0, "key", ModelProfile.ApiStyle.OPENAI);
        ModelClient.ModelResult fromLocal = result("local");
        ModelClient.ModelResult fromHosted = result("hosted");
        when(local.call("requirements", unkeyed, "system", "user")).thenReturn(fromLocal);
        when(hosted.call("requirements", keyed, "system", "user")).thenReturn(fromHosted);
        ModelClient.ModelResult fromOpenAi = result("openai");
        when(openAi.call("requirements", openAiStyle, "system", "user")).thenReturn(fromOpenAi);
        LiveModelClient live = new LiveModelClient(local, hosted, openAi);

        assertThat(live.call("requirements", unkeyed, "system", "user")).isSameAs(fromLocal);
        assertThat(live.call("requirements", keyed, "system", "user")).isSameAs(fromHosted);
        assertThat(live.call("requirements", openAiStyle, "system", "user")).isSameAs(fromOpenAi);
    }

    private static ModelProfile profile(String name, String key) {
        return new ModelProfile(name, name + "-model", URI.create("http://localhost:11434"), 32768, 1024,
                Duration.ofSeconds(2), 1, 2, 0.0, key);
    }

    private static ModelClient.ModelResult result(String text) {
        return new ModelClient.ModelResult(text, ModelClient.FinishReason.COMPLETE, null, null, Duration.ZERO);
    }
}
