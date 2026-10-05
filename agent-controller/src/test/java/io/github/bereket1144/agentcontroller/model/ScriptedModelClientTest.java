package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScriptedModelClientTest {

    @Test
    @DisplayName("AC-AGT-3.7 a scripted model returns prepared answers and failures in order")
    void preparedCallsAreDeterministic() {
        ScriptedModelClient client = new ScriptedModelClient();
        ModelProfile profile = new ModelProfile("local", "scripted", URI.create("http://localhost"),
                1000, 100, Duration.ofSeconds(1), 1, 0, 0, null);
        ModelClient.ModelResult answer = new ModelClient.ModelResult("answer", ModelClient.FinishReason.COMPLETE,
                null, null, Duration.ZERO);
        ModelClient.ModelCallException failure = new ModelClient.ModelCallException(
                ModelClient.FailureReason.TIMED_OUT, "local");
        client.addResult(answer);
        client.addFailure(failure);

        assertThat(client.call("requirements", profile, "system-1", "user-1")).isSameAs(answer);
        assertThatThrownBy(() -> client.call("design", profile, "system-2", "user-2")).isSameAs(failure);
        assertThat(client.calls()).extracting(ScriptedModelClient.Call::role)
                .containsExactly("requirements", "design");
    }

    @Test
    @DisplayName("AC-AGT-3.7 an unprepared scripted call fails the test clearly")
    void missingScriptFailsClearly() {
        ScriptedModelClient client = new ScriptedModelClient();

        assertThatThrownBy(() -> client.call("requirements", null, "system", "user"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No scripted model answer prepared for call 1");
    }
}
