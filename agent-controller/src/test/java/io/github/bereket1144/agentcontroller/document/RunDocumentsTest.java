package io.github.bereket1144.agentcontroller.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.workspace.ChangeSet;
import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import io.github.bereket1144.agentcontroller.workspace.RunWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Uses the real git command line and a temporary base folder; only the artifact store is a stand-in. */
class RunDocumentsTest {

    private static final String SUMMARY = "summary-of-a-stored-artifact";
    private static final String FINGERPRINT = "fingerprint-of-a-stored-artifact";
    private static final String MODEL = "model-of-the-producing-call";

    @TempDir
    Path temp;

    private final UUID runId = UUID.randomUUID();
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private RunWorkspace workspaces;
    private RunDocuments documents;
    private Path root;

    @BeforeEach
    void setUp() {
        workspaces = new RunWorkspace(temp.resolve("workspaces").toString());
        documents = new RunDocuments(artifacts, workspaces);
        root = temp.resolve("workspaces").resolve(runId.toString());
    }

    @Test
    @DisplayName("AC-AGT-21.1 the README, the five documents and one file for each diagram are written from the latest versions, in one commit, replacing the README that was there")
    void writesTheFilesFromTheLatestVersionsInOneCommit() throws Exception {
        givenAllArtifacts();
        workspaces.commit(runId, new ChangeSet(List.of(new FileChange("README.md", "written by development"))));

        Optional<String> commit = documents.write(runId);

        assertThat(commit).hasValue(git("rev-parse", "HEAD"));
        assertThat(git("rev-list", "--count", "HEAD")).isEqualTo("2");
        assertThat(git("show", "--name-only", "--format=", "HEAD").lines()).containsExactlyInAnyOrder(
                "README.md", "docs/requirements.md", "docs/design.md", "docs/plan.md", "docs/qa.md",
                "docs/review.md", "docs/diagrams/1.mmd", "docs/diagrams/2.mmd");
        assertThat(read("README.md")).isEqualTo("""
                # README

                ## Setup

                s

                ## Usage

                u

                ## Architecture

                a

                ## Examples

                - e

                ## Known limits

                - l

                ## Evidence links

                - l

                """);
        assertThat(read("docs/requirements.md")).startsWith("# Requirements").contains("S-1");
        assertThat(read("docs/plan.md")).startsWith("# Plan").contains("t1");
        assertThat(read("docs/qa.md")).startsWith("# QA results").contains("q1");
        assertThat(read("docs/review.md")).startsWith("# Review results").contains("r1");
        // The second version of the design is the one written, with its diagrams as code and as files.
        assertThat(read("docs/design.md")).startsWith("# Design").contains("new").doesNotContain("old")
                .contains("```mermaid\ngraph TD\n  A-->B\n```", "```mermaid\nflowchart LR\n  C-->D\n```");
        assertThat(read("docs/diagrams/1.mmd")).isEqualTo("graph TD\n  A-->B");
        assertThat(read("docs/diagrams/2.mmd")).isEqualTo("flowchart LR\n  C-->D");
    }

    @Test
    @DisplayName("AC-AGT-21.2 writing the same versions again gives identical files and no second commit")
    void writingTheSameVersionsAgainMakesNoSecondCommit() throws Exception {
        givenAllArtifacts();
        Optional<String> first = documents.write(runId);

        Optional<String> second = documents.write(runId);

        assertThat(second).isEmpty();
        assertThat(git("rev-parse", "HEAD")).isEqualTo(first.orElseThrow());
        assertThat(git("rev-list", "--count", "HEAD")).isEqualTo("1");
        assertThat(git("status", "--porcelain")).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-21.3 the commit author is controller and the message names the run and the kinds and versions written")
    void theCommitIsByTheControllerAndNamesWhatWasWritten() throws Exception {
        when(artifacts.forRun(runId)).thenReturn(List.of(
                artifact("requirements", 1, "{\"stories\":[]}"),
                artifact("design", 1, "{\"dataFlow\":\"old\"}"),
                artifact("design", 2, "{\"dataFlow\":\"new\"}")));

        documents.write(runId);

        assertThat(git("log", "-1", "--format=%an <%ae>")).isEqualTo("controller <controller@agent.invalid>");
        assertThat(git("log", "-1", "--format=%cn <%ce>")).isEqualTo("controller <controller@agent.invalid>");
        // Only the kinds that exist are named, each with the version that was written.
        assertThat(git("log", "-1", "--format=%s"))
                .contains(runId.toString()).endsWith("requirements v1, design v2");
    }

    @Test
    @DisplayName("AC-AGT-21.4 the files hold the artifact content only, not the summary, fingerprint or model of a stored artifact")
    void theFilesHoldTheArtifactContentOnly() throws Exception {
        givenAllArtifacts();

        documents.write(runId);

        try (Stream<Path> files = Files.walk(root)) {
            List<Path> written = files.filter(Files::isRegularFile)
                    .filter(file -> !root.relativize(file).startsWith(".git")).toList();
            assertThat(written).hasSize(8);
            for (Path file : written) {
                assertThat(Files.readString(file)).doesNotContain(SUMMARY, FINGERPRINT, MODEL);
            }
        }
    }

    private void givenAllArtifacts() {
        when(artifacts.forRun(runId)).thenReturn(List.of(
                artifact("requirements", 1, "{\"stories\":[{\"id\":\"S-1\"}]}"),
                artifact("design", 1, "{\"dataFlow\":\"old\",\"diagrams\":[\"graph TD\"]}"),
                artifact("design", 2, "{\"dataFlow\":\"new\","
                        + "\"diagrams\":[\"graph TD\\n  A-->B\",\"flowchart LR\\n  C-->D\"]}"),
                artifact("plan", 1, "{\"tasks\":[\"t1\"]}"),
                artifact("development", 1, "{\"changes\":[]}"),
                artifact("qa", 1, "{\"proposedTests\":[\"q1\"]}"),
                artifact("review", 1, "{\"findings\":[\"r1\"]}"),
                artifact("documentation", 1, "{\"setup\":\"s\",\"usage\":\"u\",\"architecture\":\"a\","
                        + "\"examples\":[\"e\"],\"knownLimits\":[\"l\"],\"evidenceLinks\":[\"l\"]}")));
    }

    private StoredArtifact artifact(String kind, int version, String content) {
        return new StoredArtifact(runId, kind, version, JsonMapper.shared().readTree(content), SUMMARY, FINGERPRINT,
                kind, "1", "local", MODEL, List.of());
    }

    private String read(String path) throws Exception {
        return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }

    /** Runs git with a fixed identity of its own, so that a test never depends on the user's git settings. */
    private String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=test",
                "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }
}
