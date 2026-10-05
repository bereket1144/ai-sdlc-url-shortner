package io.github.bereket1144.agentcontroller.document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.StoredArtifact;
import io.github.bereket1144.agentcontroller.workspace.ChangeSet;
import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import io.github.bereket1144.agentcontroller.workspace.RunWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Writes what the stages produced as readable files in the run's own repository: the README from the documentation
 * artifact, one document for each of the requirements, the design, the plan, the QA results and the review results,
 * and one file for each diagram source of the design. Each file is made from the latest version of its artifact, by
 * plain code and without a model, and holds that artifact's content only. All files go into one commit by the
 * controller. It exists only when the development agent is on, because only then there is a workspace.
 */
@Service
@ConditionalOnProperty(name = "agent.stages.development", havingValue = "agent")
public class RunDocuments {

    private static final Logger log = LoggerFactory.getLogger(RunDocuments.class);
    private static final String AUTHOR = "controller";
    /** The artifact kinds in the order they are written, each with its file and how its content becomes text. */
    private static final List<Document> DOCUMENTS = List.of(
            new Document("documentation", "README.md", RunDocuments::readme),
            document("requirements", "docs/requirements.md", "Requirements"),
            document("design", "docs/design.md", "Design"),
            document("plan", "docs/plan.md", "Plan"),
            document("qa", "docs/qa.md", "QA results"),
            document("review", "docs/review.md", "Review results"));

    private final ArtifactStore artifacts;
    private final RunWorkspace workspaces;

    public RunDocuments(ArtifactStore artifacts, RunWorkspace workspaces) {
        this.artifacts = artifacts;
        this.workspaces = workspaces;
    }

    /**
     * Writes the files of every artifact kind the run has, replacing a file that is already at its path, and commits
     * them once as {@code controller}. Writing the same versions again changes no file and makes no commit.
     *
     * @return the id of the new commit, or empty when there was nothing new to commit
     */
    public Optional<String> write(UUID runId) {
        Map<String, StoredArtifact> latest = latestOfEachKind(runId);
        List<FileChange> files = new ArrayList<>();
        List<String> written = new ArrayList<>();
        for (Document document : DOCUMENTS) {
            StoredArtifact artifact = latest.get(document.kind());
            if (artifact != null) {
                files.add(new FileChange(document.path(), document.render().apply(artifact.content())));
                written.add(artifact.kind() + " v" + artifact.version());
            }
        }
        StoredArtifact design = latest.get("design");
        if (design != null) {
            JsonNode sources = design.content().path(MarkdownRenderer.DIAGRAMS);
            for (int index = 0; index < sources.size(); index++) {
                files.add(new FileChange("docs/diagrams/" + (index + 1) + ".mmd",
                        MarkdownRenderer.text(sources.get(index))));
            }
        }
        if (files.isEmpty()) {
            log.info("event=documents_skipped runId={} reason=\"no artifact to write\"", runId);
            return Optional.empty();
        }
        Optional<String> commit = workspaces.commitIfChanged(runId, new ChangeSet(files), AUTHOR,
                "controller: write documents for run " + runId + " from " + String.join(", ", written));
        // Kinds, versions and counts only: the content of an artifact is never logged.
        log.info("event=documents_written runId={} files={} artifacts={} commit={}",
                runId, files.size(), written, commit.orElse("none"));
        return commit;
    }

    private Map<String, StoredArtifact> latestOfEachKind(UUID runId) {
        Map<String, StoredArtifact> latest = new HashMap<>();
        for (StoredArtifact artifact : artifacts.forRun(runId)) {
            latest.merge(artifact.kind(), artifact, (kept, other) -> other.version() > kept.version() ? other : kept);
        }
        return latest;
    }

    private static Document document(String kind, String path, String title) {
        return new Document(kind, path, content -> MarkdownRenderer.render(title, content));
    }

    /** The README: the sections of the documentation artifact in a fixed order. */
    private static String readme(JsonNode documentation) {
        return "# README\n\n"
                + readmeSection(documentation, "Setup", "setup")
                + readmeSection(documentation, "Usage", "usage")
                + readmeSection(documentation, "Architecture", "architecture")
                + readmeSection(documentation, "Examples", "examples")
                + readmeSection(documentation, "Known limits", "knownLimits")
                + readmeSection(documentation, "Evidence links", "evidenceLinks");
    }

    private static String readmeSection(JsonNode documentation, String heading, String field) {
        JsonNode value = Objects.requireNonNull(documentation.get(field),
                "The documentation artifact has no field " + field);
        return MarkdownRenderer.section(heading, value);
    }

    private record Document(String kind, String path, Function<JsonNode, String> render) { }
}
