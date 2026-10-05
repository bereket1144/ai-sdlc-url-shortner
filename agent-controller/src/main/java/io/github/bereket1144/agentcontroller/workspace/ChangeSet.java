package io.github.bereket1144.agentcontroller.workspace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

/**
 * The files the development role changes: each with a path and its full new content. This type also holds the one
 * rule for where a change may write and how large a file may be, so that the answer check and the workspace apply
 * the same rule.
 */
public record ChangeSet(List<FileChange> files) {

    static final int MAX_FILE_BYTES = 100_000;

    public ChangeSet {
        files = List.copyOf(files);
    }

    /** One file: a path relative to the workspace and the complete new text of the file, never a difference. */
    public record FileChange(String path, String content) {
        public FileChange {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }

    /** Reads the {@code changes} array of an answer that the answer check has already accepted. */
    public static ChangeSet from(JsonNode changes) {
        List<FileChange> files = new ArrayList<>();
        for (JsonNode change : changes) {
            files.add(new FileChange(change.get("path").asString(), change.get("content").asString()));
        }
        return new ChangeSet(files);
    }

    /**
     * Why a path may never be written, as a phrase that follows "Change N", or null when it may. A change may write any
     * relative path inside the run's workspace except one inside the repository's own {@code .git} folder.
     */
    static String pathProblem(String path) {
        if (path.isBlank()) {
            return "has a blank path";
        }
        if (path.matches("(?s)([/\\\\]|[A-Za-z]:).*")) {
            return "has an absolute path";
        }
        if (path.contains("..")) {
            return "has a path with \"..\"";
        }
        // Compared without regard to case, because a file system that ignores case would still reach the folder.
        if (Arrays.stream(path.split("[/\\\\]")).anyMatch(part -> part.equalsIgnoreCase(".git"))) {
            return "has a path under .git";
        }
        return null;
    }
}
