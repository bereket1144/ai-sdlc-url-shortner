package io.github.bereket1144.agentcontroller.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bereket1144.agentcontroller.workspace.ChangeSet.FileChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChangeSetTest {

    @Test
    @DisplayName("AC-AGT-69.1 the changes of an answer are read as files to write and files to delete, in order")
    void readsWritesAndDeletions() {
        ChangeSet changes = ChangeSet.from(JsonMapper.shared().readTree(
                "[{\"path\":\"a.py\",\"content\":\"x\"},{\"path\":\"b.py\",\"delete\":true}]"));

        assertThat(changes.files()).containsExactly(new FileChange("a.py", "x"), FileChange.deletion("b.py"));
        assertThat(changes.files()).extracting(FileChange::delete).containsExactly(false, true);
    }

    @Test
    @DisplayName("AC-AGT-69.1 a deletion that carries content cannot be built")
    void aDeletionHasNoContent() {
        assertThatThrownBy(() -> new FileChange("a.py", "x", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a deletion has no content");
    }
}
