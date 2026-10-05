package io.github.bereket1144.agentcontroller.modelcall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Outcome;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.StoredAttempt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("AC-AGT-10.1 loading an exported recording gives the same records, failed calls included")
    void loadingWhatWasExportedGivesTheSameRecords() {
        List<StoredAttempt> attempts = List.of(
                StoredAttempts.failed("requirements", "a".repeat(64), Outcome.TIMED_OUT),
                StoredAttempts.answered("requirements", "a".repeat(64), Outcome.SUCCESS,
                        "{\"stories\":[]}\nwith \"quotes\"", FinishReason.COMPLETE));
        Path file = directory.resolve("run.json");

        Recording.write(file, attempts);

        assertThat(Recording.read(file)).isEqualTo(attempts);
    }

    @Test
    @DisplayName("AC-AGT-10.1 a recording that cannot be read or written fails and names the file")
    void unreadableOrUnwritableRecordingNamesTheFile() throws Exception {
        Path missing = directory.resolve("missing.json");
        Path invalid = Files.writeString(directory.resolve("invalid.json"), "not a recording");
        Path noFolder = directory.resolve("no-folder").resolve("run.json");

        assertThatThrownBy(() -> Recording.read(missing)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing.toString());
        assertThatThrownBy(() -> Recording.read(invalid)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(invalid.toString());
        assertThatThrownBy(() -> Recording.write(noFolder, List.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(noFolder.toString());
    }
}
