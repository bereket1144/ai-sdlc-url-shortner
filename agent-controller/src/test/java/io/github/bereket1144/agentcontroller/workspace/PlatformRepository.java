package io.github.bereket1144.agentcontroller.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The platform repository of a test: a real git repository in a temporary folder, on the branch {@code main}, with one
 * commit. The commit has a committed identity of its own, so no test depends on the git settings of the user.
 */
public final class PlatformRepository {

    private PlatformRepository() { }

    /** A repository with the files of a small platform: a README and a build file, outside any product folder. */
    public static Path create(Path folder) {
        return create(folder, Map.of("README.md", "platform\n", "agent-controller/pom.xml", "<project/>\n"));
    }

    /** A repository on {@code main} with one commit that holds exactly these files, each by its path. */
    public static Path create(Path folder, Map<String, String> files) {
        try {
            Files.createDirectories(folder);
            git(folder, "init", "--quiet", "--initial-branch=main");
            for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
                Path target = folder.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
            git(folder, "add", "--all");
            git(folder, "commit", "--quiet", "-m", "platform");
            return folder;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Runs git in the folder with a fixed identity of its own and returns what it printed; fails when git fails. */
    public static String git(Path folder, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=test",
                "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertThat(process.waitFor()).as(output).isZero();
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
