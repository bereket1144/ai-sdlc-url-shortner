package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Plain structural test: it reads the sources of the application, which `./mvnw verify` runs from agent-controller/.
class ApprovalWritersTest {

    private static final Path SOURCES = Path.of("src/main/java");

    @Test
    @DisplayName("AC-AGT-15.7 a decision is stored only by the approval service, and only the decisions operation of "
            + "the run controller calls it, so nothing an agent returns can approve or reject")
    void onlyTheDecisionsOperationDecides() throws IOException {
        // The repository and the entity are not visible outside the package, so another package cannot even name them.
        assertThat(Modifier.isPublic(ApprovalRepository.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(ApprovalDecision.class.getModifiers())).isFalse();

        assertThat(sourcesMentioning("ApprovalRepository"))
                .containsExactlyInAnyOrder("ApprovalRepository.java", "ApprovalService.java");
        assertThat(sourcesMentioning("new ApprovalDecision(")).containsExactly("ApprovalService.java");
        assertThat(sourcesMentioning(".decide(")).containsExactly("RunController.java");
    }

    private static List<String> sourcesMentioning(String text) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> read(file).contains(text))
                    .map(file -> file.getFileName().toString()).toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
