package io.github.bereket1144.agentcontroller.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.github.bereket1144.agentcontroller.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

// UI-1 on the full application, over a real port: the welcome page is a forward to index.html, which MockMvc does not
// follow, so only a real request shows what a browser gets at "/".
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReviewerPageTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("AC-UI-1.10 GET / answers 200 with an HTML page that loads the page's own script and style files")
    void pageIsServedAndLoadsItsFiles() throws Exception {
        HttpResponse<String> page = get("/", "text/html");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
        assertThat(page.body()).contains("src=\"app.js\"").contains("href=\"app.css\"");
    }

    @Test
    @DisplayName("AC-UI-1.10 the script and the style file the page loads are served")
    void scriptAndStyleAreServed() throws Exception {
        HttpResponse<String> script = get("/app.js", "*/*");
        HttpResponse<String> style = get("/app.css", "text/css,*/*");

        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).contains("javascript"));
        assertThat(style.statusCode()).isEqualTo(200);
        assertThat(style.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/css"));
    }

    private HttpResponse<String> get(String path, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", accept).GET().build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
