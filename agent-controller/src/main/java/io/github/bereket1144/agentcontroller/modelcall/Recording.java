package io.github.bereket1144.agentcontroller.modelcall;

import java.nio.file.Path;
import java.util.List;

import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.StoredAttempt;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * A recording is a run's call records written as one JSON file, so that replay mode can serve the same answers and
 * failures later. Every stored field is written, so reading a file gives back what was written.
 */
public final class Recording {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Recording() {
    }

    /** Writes the records, in the order given, to the file; fails with the file named when it cannot be written. */
    public static void write(Path file, List<StoredAttempt> attempts) {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), attempts);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot write the model call recording " + file, exception);
        }
    }

    /** Reads the records in the order they were written; fails with the file named when it is missing or invalid. */
    public static List<StoredAttempt> read(Path file) {
        try {
            return List.of(MAPPER.readValue(file.toFile(), StoredAttempt[].class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot read the model call recording " + file, exception);
        }
    }
}
