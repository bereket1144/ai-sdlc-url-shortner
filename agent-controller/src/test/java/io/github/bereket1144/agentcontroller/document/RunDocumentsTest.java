package io.github.bereket1144.agentcontroller.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.run.LineageResponse;
import io.github.bereket1144.agentcontroller.run.LineageResponse.LineageArtifact;
import io.github.bereket1144.agentcontroller.run.RunDetails;
import io.github.bereket1144.agentcontroller.workspace.ChangeSet;
import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import io.github.bereket1144.agentcontroller.workspace.PlatformRepository;
import io.github.bereket1144.agentcontroller.workspace.RunWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uses the real git command line, a temporary platform repository and a temporary base folder; only the artifact
 * store and the lineage are stand-ins.
 */
class RunDocumentsTest {

    private static final String SUMMARY = "summary-of-a-stored-artifact";
    private static final String FINGERPRINT = "fingerprint-of-a-stored-artifact";
    private static final String MODEL = "model-of-the-producing-call";
    private static final String PRODUCT = "apps/demo";

    @TempDir
    Path temp;

    private final UUID runId = UUID.randomUUID();
    private final ArtifactStore artifacts = mock(ArtifactStore.class);
    private final RunDetails details = mock(RunDetails.class);
    private RunWorkspace workspaces;
    private RunDocuments documents;
    private Path root;

    @BeforeEach
    void setUp() {
        Path platform = PlatformRepository.create(temp.resolve("platform"));
        workspaces = new RunWorkspace(temp.resolve("workspaces").toString(), platform.toString(), "HEAD", PRODUCT);
        documents = new RunDocuments(artifacts, workspaces, details);
        root = temp.resolve("workspaces").resolve(runId.toString());
        when(details.lineage(runId)).thenReturn(new LineageResponse(
                List.of(new LineageArtifact("design", 2, FINGERPRINT, SUMMARY, List.of())), "abc123", "PASSED",
                List.of()));
    }

    @Test
    @DisplayName("AC-AGT-21.1, AC-AGT-67.3 the README, the five documents and one file for each diagram are written "
            + "under the product folder from the latest versions, in one commit, replacing the README that was there")
    void writesTheFilesFromTheLatestVersionsInOneCommit() throws Exception {
        givenAllArtifacts();
        workspaces.commit(runId, new ChangeSet(List.of(new FileChange("README.md", "written by development"))));

        Optional<String> commit = documents.write(runId);

        assertThat(commit).hasValue(git("rev-parse", "HEAD"));
        // The platform's commit, the development commit and this one.
        assertThat(git("rev-list", "--count", "HEAD")).isEqualTo("3");
        assertThat(git("show", "--name-only", "--format=", "HEAD").lines()
                .filter(path -> path.startsWith(PRODUCT + "/"))).containsExactlyInAnyOrder(
                PRODUCT + "/README.md", PRODUCT + "/docs/requirements.md", PRODUCT + "/docs/design.md",
                PRODUCT + "/docs/plan.md", PRODUCT + "/docs/qa.md", PRODUCT + "/docs/review.md",
                PRODUCT + "/docs/diagrams/1.mmd", PRODUCT + "/docs/diagrams/2.mmd");
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
    @DisplayName("AC-AGT-21.2, AC-AGT-67.3 writing the same versions again gives identical files, the evidence "
            + "included, and no second commit")
    void writingTheSameVersionsAgainMakesNoSecondCommit() throws Exception {
        givenAllArtifacts();
        Optional<String> first = documents.write(runId);

        Optional<String> second = documents.write(runId);

        assertThat(second).isEmpty();
        assertThat(git("rev-parse", "HEAD")).isEqualTo(first.orElseThrow());
        assertThat(git("rev-list", "--count", "HEAD")).isEqualTo("2");
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
    @DisplayName("AC-AGT-21.4 the documents hold the artifact content only, not the summary, fingerprint or model of a stored artifact")
    void theFilesHoldTheArtifactContentOnly() throws Exception {
        givenAllArtifacts();

        documents.write(runId);

        try (Stream<Path> files = Files.walk(root.resolve(PRODUCT))) {
            List<Path> written = files.filter(Files::isRegularFile).toList();
            assertThat(written).hasSize(8);
            for (Path file : written) {
                assertThat(Files.readString(file)).doesNotContain(SUMMARY, FINGERPRINT, MODEL);
            }
        }
    }

    @Test
    @DisplayName("AC-AGT-67.3 the same commit holds the evidence under runs/<run-id>: the latest version of each "
            + "artifact as JSON, and the run's lineage as JSON")
    void theCommitAlsoHoldsTheEvidenceOfTheRun() throws Exception {
        givenAllArtifacts();

        documents.write(runId);

        String evidence = "runs/" + runId + "/";
        assertThat(git("show", "--name-only", "--format=", "HEAD").lines()
                .filter(path -> path.startsWith(evidence))).containsExactlyInAnyOrder(
                evidence + "lineage.json", evidence + "artifacts/design-v2.json",
                evidence + "artifacts/development-v1.json", evidence + "artifacts/documentation-v1.json",
                evidence + "artifacts/plan-v1.json", evidence + "artifacts/qa-v1.json",
                evidence + "artifacts/requirements-v1.json", evidence + "artifacts/review-v1.json");
        // Only the content of the latest version, as it was stored.
        JsonNode design = json(evidence + "artifacts/design-v2.json");
        assertThat(design.path("dataFlow").asString()).isEqualTo("new");
        assertThat(design.toString()).doesNotContain(SUMMARY, FINGERPRINT, MODEL);
        JsonNode lineage = json(evidence + "lineage.json");
        assertThat(lineage.path("commit").asString()).isEqualTo("abc123");
        assertThat(lineage.path("verificationOutcome").asString()).isEqualTo("PASSED");
        assertThat(lineage.path("artifacts").get(0).path("kind").asString()).isEqualTo("design");
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

    /** A JSON file of the workspace, by its path from the root of the workspace. */
    private JsonNode json(String path) throws Exception {
        return JsonMapper.shared().readTree(Files.readString(root.resolve(path), StandardCharsets.UTF_8));
    }

    /** A file of the product folder, by its path inside that folder. */
    private String read(String path) throws Exception {
        return Files.readString(root.resolve(PRODUCT).resolve(path), StandardCharsets.UTF_8);
    }

    private String git(String... arguments) {
        return PlatformRepository.git(root, arguments);
    }
}
