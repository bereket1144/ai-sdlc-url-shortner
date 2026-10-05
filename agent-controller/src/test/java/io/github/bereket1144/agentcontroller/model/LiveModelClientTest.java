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
    @DisplayName("AC-AGT-62.1 a profile with a key is called with the hosted client and one without a key with the local client")
    void sendsEachCallToTheClientOfItsProfile() {
        OllamaModelClient local = mock(OllamaModelClient.class);
        HostedModelClient hosted = mock(HostedModelClient.class);
        ModelProfile unkeyed = profile("local", null);
        ModelProfile keyed = profile("hosted", "key");
        ModelClient.ModelResult fromLocal = result("local");
        ModelClient.ModelResult fromHosted = result("hosted");
        when(local.call("requirements", unkeyed, "system", "user")).thenReturn(fromLocal);
        when(hosted.call("requirements", keyed, "system", "user")).thenReturn(fromHosted);
        LiveModelClient live = new LiveModelClient(local, hosted);

        assertThat(live.call("requirements", unkeyed, "system", "user")).isSameAs(fromLocal);
        assertThat(live.call("requirements", keyed, "system", "user")).isSameAs(fromHosted);
    }

    private static ModelProfile profile(String name, String key) {
        return new ModelProfile(name, name + "-model", URI.create("http://localhost:11434"), 32768, 1024,
                Duration.ofSeconds(2), 1, 2, 0.0, key);
    }

    private static ModelClient.ModelResult result(String text) {
        return new ModelClient.ModelResult(text, ModelClient.FinishReason.COMPLETE, null, null, Duration.ZERO);
    }
}
