package io.github.bereket1144.agentcontroller.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the text of the files in a run's workspace, so an agent can be shown the code it wrote before. The folder is
 * a repository that only the controller writes, by committing change sets, so every file in it, apart from the
 * {@code .git} folder, is a tracked file. A binary file, a file that is over the size limit, a link, and every file
 * past the count or the total size limit are left out and only counted: the bounds keep the answer small enough for a
 * prompt. The files come in order of their path, so the same workspace always gives the same answer.
 */
@Component
public class WorkspaceFiles {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFiles.class);
    static final int MAX_FILES = 30;
    static final int MAX_TOTAL_CHARS = 40_000;
    static final int MAX_FILE_BYTES = 10_000;

    private final int maxFiles;
    private final int maxTotalChars;
    private final int maxFileBytes;

    public WorkspaceFiles() {
        this(MAX_FILES, MAX_TOTAL_CHARS, MAX_FILE_BYTES);
    }

    WorkspaceFiles(int maxFiles, int maxTotalChars, int maxFileBytes) {
        this.maxFiles = maxFiles;
        this.maxTotalChars = maxTotalChars;
        this.maxFileBytes = maxFileBytes;
    }

    /** A file's path, relative to the workspace and with {@code /} between the folders, and its whole text. */
    public record FileText(String path, String text) { }

    /**
     * @param files   the files that were read, in path order
     * @param omitted how many files were left out: binary, oversized, or past a bound
     */
    public record Snapshot(List<FileText> files, int omitted) {
        public Snapshot {
            files = List.copyOf(files);
        }
    }

    public Snapshot read(Path root) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    return !dir.equals(root) && dir.getFileName().toString().equals(".git")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    // A link could lead out of the workspace, so it is never followed or read.
                    if (attributes.isRegularFile()) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot list the files of the workspace", exception);
        }
        found.sort(Comparator.comparing(file -> relative(root, file)));

        List<FileText> files = new ArrayList<>();
        int totalChars = 0;
        for (Path file : found) {
            String text = files.size() < maxFiles ? textOf(file) : null;
            if (text != null && totalChars + text.length() <= maxTotalChars) {
                files.add(new FileText(relative(root, file), text));
                totalChars += text.length();
            }
        }
        int omitted = found.size() - files.size();
        // Counts only: the paths and the text of generated code are never logged.
        log.info("event=workspace_files_read files={} omitted={} chars={}", files.size(), omitted, totalChars);
        return new Snapshot(files, omitted);
    }

    /** The text of the file, or null when it is oversized or binary: not valid UTF-8, or holding a zero byte. */
    private String textOf(Path file) {
        try {
            if (Files.size(file) > maxFileBytes) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            for (byte value : bytes) {
                if (value == 0) {
                    return null;
                }
            }
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException binary) {
            return null;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read a file of the workspace", exception);
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
