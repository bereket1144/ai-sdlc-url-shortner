package io.github.bereket1144.agentcontroller.workspace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

/**
 * The files the development role changes: each with a path and its full new content, or a path to delete. This type
 * also holds the one rule for where a change may write and how large a file may be, so that the answer check and the
 * workspace apply the same rule.
 */
public record ChangeSet(List<FileChange> files) {

    static final int MAX_FILE_BYTES = 100_000;
    /** Well under the limit of the file system (1,024 on macOS) once the folder of the workspace is added. */
    static final int MAX_PATH_BYTES = 500;
    /** The longest name of one file or folder that the usual file systems accept. */
    static final int MAX_NAME_BYTES = 255;

    public ChangeSet {
        files = List.copyOf(files);
    }

    /**
     * One file: a path relative to the workspace and either the complete new text of the file, never a difference, or
     * the order to delete the file, which has no text.
     */
    public record FileChange(String path, String content, boolean delete) {
        public FileChange {
            Objects.requireNonNull(path, "path must not be null");
            if (delete) {
                if (content != null) {
                    throw new IllegalArgumentException("a deletion has no content");
                }
            } else {
                Objects.requireNonNull(content, "content must not be null");
            }
        }

        /** A file to write with this complete text. */
        public FileChange(String path, String content) {
            this(path, content, false);
        }

        /** A file to delete. */
        public static FileChange deletion(String path) {
            return new FileChange(path, null, true);
        }
    }

    /** Reads the {@code changes} array of an answer that the answer check has already accepted. */
    public static ChangeSet from(JsonNode changes) {
        List<FileChange> files = new ArrayList<>();
        for (JsonNode change : changes) {
            String path = change.get("path").asString();
            // The answer check has made sure that a "delete" is true and that such a change has no content.
            files.add(change.has("delete") ? FileChange.deletion(path)
                    : new FileChange(path, change.get("content").asString()));
        }
        return new ChangeSet(files);
    }

    /**
     * Why a path may never be written or deleted, as a phrase that follows "Change N", or null when it may. A change
     * may write or delete any relative path inside the run's workspace except one inside the repository's own
     * {@code .git} folder, and the path must be one that the file system can hold, so that a bad path is refused here
     * and not when it is written.
     */
    static String pathProblem(String path) {
        if (path.isBlank()) {
            return "has a blank path";
        }
        if (path.matches("(?s)([/\\\\]|[A-Za-z]:).*")) {
            return "has an absolute path";
        }
        if (path.indexOf('\0') >= 0) {
            return "has a path with a NUL character";
        }
        if (path.contains("..")) {
            return "has a path with \"..\"";
        }
        List<String> parts = parts(path);
        if (parts.isEmpty()) {
            return "has a path without a file name";
        }
        // Compared without regard to case, because a file system that ignores case would still reach the folder.
        if (parts.stream().anyMatch(part -> part.equalsIgnoreCase(".git"))) {
            return "has a path under .git";
        }
        if (byteLength(path) > MAX_PATH_BYTES) {
            return "has a path longer than " + MAX_PATH_BYTES + " bytes";
        }
        if (parts.stream().anyMatch(part -> byteLength(part) > MAX_NAME_BYTES)) {
            return "has a file or folder name longer than " + MAX_NAME_BYTES + " bytes";
        }
        return null;
    }

    /** The folders and the file name of a path, in order, without empty parts and "." parts. */
    static List<String> parts(String path) {
        return Arrays.stream(path.split("[/\\\\]")).filter(part -> !part.isEmpty() && !part.equals(".")).toList();
    }

    private static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
