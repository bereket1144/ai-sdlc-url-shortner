package io.github.bereket1144.agentcontroller.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ChangeSetCheckTest {

    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final ChangeSetCheck check = new ChangeSetCheck();

    @Test
    @DisplayName("AC-AGT-62.6 a change set with paths in the product folder and a run folder is accepted")
    void acceptsAllowedPaths() {
        assertThat(check.validate(answer(change("apps/url-shortener/app.py", "print(1)"),
                change("runs/6f9619ff-8b86-d011-b42d-00c04fc964ff/notes.md", "")))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedAnswers")
    @DisplayName("AC-AGT-62.6 an unsafe, empty or oversized change set is refused")
    void refusesUnsafeChanges(String description, JsonNode answer, String message) {
        assertThat(check.validate(answer)).containsExactly(message);
    }

    static Stream<Arguments> refusedAnswers() {
        String outside = "Change 1 has a path outside apps/url-shortener/ and runs/<run-id>/";
        return Stream.of(
                Arguments.of("a path outside the allowed folders", answer(change("src/Main.java", "x")), outside),
                Arguments.of("an absolute path", answer(change("/etc/passwd", "x")),
                        "Change 1 has an absolute path"),
                Arguments.of("a path with ..", answer(change("apps/url-shortener/../../x", "x")),
                        "Change 1 has a path with \"..\""),
                Arguments.of("an empty change set", answer(),
                        "The change set is empty: list at least one file"),
                Arguments.of("a file over the size limit",
                        answer(change("apps/url-shortener/big.txt", "x".repeat(ChangeSet.MAX_FILE_BYTES + 1))),
                        "Change 1 is larger than " + ChangeSet.MAX_FILE_BYTES + " bytes"));
    }

    private static String change(String path, String content) {
        return MAPPER.createObjectNode().put("path", path).put("content", content).toString();
    }

    private static JsonNode answer(String... changes) {
        return MAPPER.readTree("{\"changes\":" + List.of(changes) + "}");
    }
}
