package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.model.ModelProfile.ApiStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class OpenAiCompatibleModelClientTest {

    private static final String KEY = "PRIVATE_KEY_MARKER";

    @Test
    @DisplayName("AC-AGT-63.2, 63.3 a call sends the bearer key, model, messages and limits and reads text, finish reason and counts")
    void sendsRequestAndReadsResponse() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (Stub stub = new Stub(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"choices":[{"message":{"role":"assistant","content":"model answer"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":12,"completion_tokens":4}}""");
        })) {
            ModelProfile profile = profile(stub.address());

            ModelClient.ModelResult result = client(profile).call("requirements", profile, "system text", "user task");

            assertThat(path.get()).isEqualTo("/api/v1/chat/completions");
            assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
            assertThat(body.get()).contains("\"model\":\"router-model\"", "\"max_tokens\":1024", "\"temperature\":0.0",
                    "\"role\":\"system\"", "\"content\":\"system text\"", "\"role\":\"user\"",
                    "\"content\":\"user task\"").doesNotContain(KEY);
            assertThat(result.text()).isEqualTo("model answer");
            assertThat(result.finishReason()).isEqualTo(ModelClient.FinishReason.COMPLETE);
            assertThat(result.promptTokens()).isEqualTo(12L);
            assertThat(result.answerTokens()).isEqualTo(4L);
        }
    }

    @Test
    @DisplayName("AC-AGT-63.3, 63.4 length is an answer limit, a leading think block is removed, and an empty answer is unreadable")
    void readsOtherFinishReasonsAndStripsThinking() throws Exception {
        AtomicReference<String> reply = new AtomicReference<>();
        try (Stub stub = new Stub(exchange -> respond(exchange, 200, reply.get()))) {
            ModelProfile profile = profile(stub.address());
            OpenAiCompatibleModelClient client = client(profile);

            reply.set("{\"choices\":[{\"message\":{\"content\":\"<think>{\\\"x\\\":1} hmm</think>\\n{\\\"a\\\":2}\"},"
                    + "\"finish_reason\":\"length\"}]}");
            ModelClient.ModelResult cut = client.call("requirements", profile, "system", "user");
            assertThat(cut.text()).isEqualTo("{\"a\":2}");
            assertThat(cut.finishReason()).isEqualTo(ModelClient.FinishReason.ANSWER_LIMIT);

            reply.set("{\"choices\":[{\"message\":{\"content\":\"<think>only thinking</think>\"},"
                    + "\"finish_reason\":\"stop\"}]}");
            assertThatThrownBy(() -> client.call("requirements", profile, "system", "user"))
                    .isInstanceOfSatisfying(ModelClient.ModelCallException.class,
                            failure -> assertThat(failure.reason())
                                    .isEqualTo(ModelClient.FailureReason.RESPONSE_UNREADABLE));
        }
    }

    @ParameterizedTest
    @CsvSource({"429, RATE_LIMITED, true", "503, SERVER_ERROR, true", "401, REQUEST_REJECTED, false",
            "402, REQUEST_REJECTED, false"})
    @DisplayName("AC-AGT-63.5 an HTTP failure has its typed reason and no response text or key")
    void mapsHttpFailure(int status, ModelClient.FailureReason expected, boolean retryable) throws Exception {
        try (Stub stub = new Stub(exchange -> respond(exchange, status, "private response body"))) {
            ModelProfile profile = profile(stub.address());

            assertThatThrownBy(() -> client(profile).call("requirements", profile, "system", "user"))
                    .isInstanceOfSatisfying(ModelClient.ModelCallException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(expected);
                        assertThat(failure.retryable()).isEqualTo(retryable);
                        assertThat(failure.getMessage()).doesNotContain("private response body", KEY);
                    });
        }
    }

    @Test
    @DisplayName("AC-AGT-63.5 an error object in a reply with status 200 is a failure")
    void anErrorBodyIsAFailure() throws Exception {
        try (Stub stub = new Stub(exchange -> respond(exchange, 200,
                "{\"error\":{\"code\":429,\"message\":\"private text\"}}"))) {
            ModelProfile profile = profile(stub.address());

            assertThatThrownBy(() -> client(profile).call("requirements", profile, "system", "user"))
                    .isInstanceOfSatisfying(ModelClient.ModelCallException.class, failure -> {
                        assertThat(failure.reason()).isEqualTo(ModelClient.FailureReason.RATE_LIMITED);
                        assertThat(failure.getMessage()).doesNotContain("private text");
                    });
        }
    }

    @Test
    @DisplayName("AC-AGT-63.6 a profile without a key is refused before any request")
    void refusesABlankKey() throws Exception {
        ModelProfile unkeyed = new ModelProfile("router", "router-model", URI.create("http://127.0.0.1:1/api/v1"),
                200000, 1024, Duration.ofSeconds(2), 1, 2, 0.0, null, ApiStyle.OPENAI);

        assertThatThrownBy(() -> client(unkeyed).call("requirements", unkeyed, "system", "user"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-AGT-63.1 the api-style setting binds, and a profile that names none is inferred from its key")
    void theStyleBindsAndIsOtherwiseInferred() {
        Map<String, Object> settings = new java.util.HashMap<>();
        settings.put("agent.models.primary", "a");
        for (String name : new String[] {"a", "b"}) {
            String prefix = "agent.models.profiles." + name + ".";
            settings.put(prefix + "model", "m");
            settings.put(prefix + "base-address", "http://localhost");
            settings.put(prefix + "context-window", "1000");
            settings.put(prefix + "answer-limit", "100");
            settings.put(prefix + "call-timeout", "5s");
            settings.put(prefix + "api-key", "k");
        }
        settings.put("agent.models.profiles.a.api-style", "openai");
        Binder binder = new Binder(new MapConfigurationPropertySource(settings));

        ModelProperties properties = binder.bind("agent.models", ModelProperties.class).get();

        assertThat(properties.profiles().get("a").style()).isEqualTo(ApiStyle.OPENAI);
        assertThat(properties.profiles().get("b").style()).isEqualTo(ApiStyle.ANTHROPIC);
    }

    private static OpenAiCompatibleModelClient client(ModelProfile profile) throws IOException {
        ModelProperties properties = new ModelProperties(Map.of("router", profile), "router", null, Map.of(), 10);
        RoleCatalog roles = new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties"));
        return new OpenAiCompatibleModelClient(new ModelProfiles(properties), roles);
    }

    private static ModelProfile profile(URI address) {
        return new ModelProfile("router", "router-model", address.resolve("/api/v1"), 200000, 1024,
                Duration.ofSeconds(2), 1, 2, 0.0, KEY, ApiStyle.OPENAI);
    }

    private static void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }

    private static final class Stub implements AutoCloseable {
        private final HttpServer server;

        private Stub(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/api/v1/chat/completions", handler);
            server.start();
        }

        private URI address() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
