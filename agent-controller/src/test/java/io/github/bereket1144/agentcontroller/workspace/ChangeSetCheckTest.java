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
    @DisplayName("AC-AGT-62.6 a change set with relative paths anywhere in the workspace is accepted")
    void acceptsAnyRelativePath() {
        assertThat(check.validate(answer(change("app.py", "print(1)"), change("tests/test_app.py", ""),
                change(".gitignore", "__pycache__/")))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedAnswers")
    @DisplayName("AC-AGT-62.6 a path under .git, an absolute path, a path with .., an empty change set, an oversized file, "
            + "or a path that cannot be written or clashes with another change is refused")
    void refusesUnsafeChanges(String description, JsonNode answer, String message) {
        assertThat(check.validate(answer)).containsExactly(message);
    }

    static Stream<Arguments> refusedAnswers() {
        return Stream.of(
                Arguments.of("a path under .git", answer(change(".git/hooks/pre-commit", "x")),
                        "Change 1 has a path under .git"),
                Arguments.of("a path under .git in another case", answer(change("src/.GIT/config", "x")),
                        "Change 1 has a path under .git"),
                Arguments.of("an absolute path", answer(change("/etc/passwd", "x")),
                        "Change 1 has an absolute path"),
                Arguments.of("a path with ..", answer(change("src/../../x", "x")),
                        "Change 1 has a path with \"..\""),
                Arguments.of("an empty change set", answer(),
                        "The change set is empty: list at least one file"),
                Arguments.of("a path with a NUL character", answer(change("a\0b.py", "x")),
                        "Change 1 has a path with a NUL character"),
                Arguments.of("a path without a file name", answer(change("./", "x")),
                        "Change 1 has a path without a file name"),
                Arguments.of("a path that is too long", answer(change("a/".repeat(300) + "x.py", "x")),
                        "Change 1 has a path longer than " + ChangeSet.MAX_PATH_BYTES + " bytes"),
                Arguments.of("a name that is too long", answer(change("x".repeat(ChangeSet.MAX_NAME_BYTES + 1), "x")),
                        "Change 1 has a file or folder name longer than " + ChangeSet.MAX_NAME_BYTES + " bytes"),
                Arguments.of("the same path twice, written two ways",
                        answer(change("src/app.py", "1"), change("./src//app.py", "2")),
                        "Change 2 repeats the path of change 1"),
                Arguments.of("a folder where an earlier change writes a file",
                        answer(change("a", "x"), change("a/b.py", "x")),
                        "Change 2 needs a folder where change 1 writes a file"),
                Arguments.of("a file where an earlier change needs a folder",
                        answer(change("a/b.py", "x"), change("a", "x")),
                        "Change 2 writes a file where change 1 needs a folder"),
                Arguments.of("a file over the size limit",
                        answer(change("big.txt", "x".repeat(ChangeSet.MAX_FILE_BYTES + 1))),
                        "Change 1 is larger than " + ChangeSet.MAX_FILE_BYTES + " bytes"));
    }

    private static String change(String path, String content) {
        return MAPPER.createObjectNode().put("path", path).put("content", content).toString();
    }

    private static JsonNode answer(String... changes) {
        return MAPPER.readTree("{\"changes\":" + List.of(changes) + "}");
    }
}
