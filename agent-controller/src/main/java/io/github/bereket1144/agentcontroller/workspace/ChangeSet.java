package io.github.bereket1144.agentcontroller.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * The files the development role changes: each with a path and its full new content. This type also holds the one
 * rule for where a change may write and how large a file may be, so that the answer check and the workspace apply
 * the same rule.
 */
public record ChangeSet(List<FileChange> files) {

    /** The product's own folder, the first of the two places a change may write. */
    public static final String PRODUCT_FOLDER = "apps/url-shortener/";
    /** The folder that holds one sub-folder per run, the second place; a run may write only to its own. */
    static final String RUN_FOLDERS = "runs/";
    static final int MAX_FILE_BYTES = 100_000;

    private static final String OUTSIDE = "has a path outside " + PRODUCT_FOLDER + " and " + RUN_FOLDERS + "<run-id>/";

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
     * Why a path may never be written, as a phrase that follows "Change N", or null when it may. The folder of any one
     * run is accepted here, because the answer check does not know the run.
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
        return path.startsWith(PRODUCT_FOLDER) || path.matches(RUN_FOLDERS + "[^/]+/.+") ? null : OUTSIDE;
    }

    /** As {@link #pathProblem(String)}, and a path in a run folder must be in the folder of this run. */
    static String pathProblem(String path, UUID runId) {
        String problem = pathProblem(path);
        if (problem == null && path.startsWith(RUN_FOLDERS) && !path.startsWith(RUN_FOLDERS + runId + "/")) {
            return OUTSIDE;
        }
        return problem;
    }
}
