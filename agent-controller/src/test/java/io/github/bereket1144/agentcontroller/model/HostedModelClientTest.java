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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class HostedModelClientTest {

    private static final String KEY = "PRIVATE_KEY_MARKER";
    private static final String COMPLETE = """
            {"id":"msg_1","type":"message","role":"assistant",
             "content":[{"type":"text","text":"model answer"}],"model":"hosted-model","stop_reason":"end_turn",
             "usage":{"input_tokens":12,"output_tokens":4}}
            """;

    @Test
    @DisplayName("AC-AGT-62.1 a hosted call sends the profile's key and limits and returns text, finish reason and token counts")
    void sendsRequestAndReadsResponse() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> version = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (Stub stub = new Stub(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, COMPLETE);
        })) {
            ModelProfile profile = profile(stub.address());

            ModelClient.ModelResult result = client(profile)
                    .call("requirements", profile, "system instructions", "user task");

            assertThat(path.get()).isEqualTo("/v1/messages");
            assertThat(key.get()).isEqualTo(KEY);
            assertThat(version.get()).isNotBlank();
            assertThat(body.get()).contains("\"model\":\"hosted-model\"", "\"max_tokens\":1024",
                    "\"temperature\":0.0", "\"system\":\"system instructions\"", "\"role\":\"user\"",
                    "\"content\":\"user task\"")
                    .doesNotContain(KEY);
            assertThat(result.text()).isEqualTo("model answer");
            assertThat(result.finishReason()).isEqualTo(ModelClient.FinishReason.COMPLETE);
            assertThat(result.promptTokens()).isEqualTo(12L);
            assertThat(result.answerTokens()).isEqualTo(4L);
            assertThat(result.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
        }
    }

    @ParameterizedTest
    @CsvSource({"429, RATE_LIMITED, true", "401, REQUEST_REJECTED, false"})
    @DisplayName("AC-AGT-62.1 a retryable and a non-retryable HTTP failure have their typed reason and no response text")
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
    @DisplayName("AC-AGT-62.1 the log of a hosted call holds neither the key, the prompt nor the answer")
    void logsMetadataOnly() throws Exception {
        try (Stub stub = new Stub(exchange -> respond(exchange, 200,
                "{\"content\":[{\"type\":\"text\",\"text\":\"PRIVATE_ANSWER_MARKER\"}],\"stop_reason\":\"end_turn\"}"))) {
            ModelProfile profile = profile(stub.address());
            Logger logger = (Logger) LoggerFactory.getLogger(HostedModelClient.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                client(profile).call("requirements", profile, "PRIVATE_SYSTEM_MARKER", "PRIVATE_USER_MARKER");

                assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains("event=model_call", "role=requirements", "profile=hosted", "outcome=SUCCESS")
                        .doesNotContain(KEY, "PRIVATE_SYSTEM_MARKER", "PRIVATE_USER_MARKER",
                                "PRIVATE_ANSWER_MARKER"));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }
    }

    private static HostedModelClient client(ModelProfile profile) throws IOException {
        ModelProperties properties = new ModelProperties(Map.of("hosted", profile), "hosted", null, Map.of(), 10);
        RoleCatalog roles = new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties"));
        return new HostedModelClient(new ModelProfiles(properties), roles);
    }

    private static ModelProfile profile(URI address) {
        return new ModelProfile("hosted", "hosted-model", address, 200000, 1024, Duration.ofSeconds(2),
                1, 2, 0.0, KEY);
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
            server.createContext("/v1/messages", handler);
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
