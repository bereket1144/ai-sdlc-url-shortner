package io.github.bereket1144.agentcontroller.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.bereket1144.agentcontroller.workspace.WorkspaceFiles.FileText;
import io.github.bereket1144.agentcontroller.workspace.WorkspaceFiles.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFilesTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("AC-AGT-65.3 the files come in path order without .git, binary and oversized files, and stop at the "
            + "bounds of count and total size")
    void readsTheTextFilesWithinTheBounds() throws IOException {
        write(".git/HEAD", "ref: refs/heads/main");
        write("tests/test_app.py", "def test_x():\n    pass\n");
        write("app.py", "print(1)\n");
        write("image.bin", "A\0B");
        write("big.txt", "x".repeat(50));
        write("zeta.py", "print(2)\n");

        Snapshot all = new WorkspaceFiles(10, 1000, 40).read(root);

        // The two left out are the binary file and the oversized one.
        assertThat(all.files()).extracting(FileText::path)
                .containsExactly("app.py", "tests/test_app.py", "zeta.py");
        assertThat(all.files().get(1).text()).isEqualTo("def test_x():\n    pass\n");
        assertThat(all.omitted()).isEqualTo(2);

        // A count bound of two keeps the first two in path order.
        Snapshot two = new WorkspaceFiles(2, 1000, 40).read(root);
        assertThat(two.files()).extracting(FileText::path).containsExactly("app.py", "tests/test_app.py");
        assertThat(two.omitted()).isEqualTo(3);

        // A total bound of 25 characters leaves out the file that would pass it and keeps the one after it.
        Snapshot small = new WorkspaceFiles(10, 25, 40).read(root);
        assertThat(small.files()).extracting(FileText::path).containsExactly("app.py", "zeta.py");
        assertThat(small.omitted()).isEqualTo(3);
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
