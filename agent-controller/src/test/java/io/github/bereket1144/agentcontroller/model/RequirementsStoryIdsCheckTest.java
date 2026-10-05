package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequirementsStoryIdsCheckTest {

    private final RequirementsStoryIdsCheck check = new RequirementsStoryIdsCheck();
    private final JsonMapper mapper = JsonMapper.shared();

    @Test
    @DisplayName("AC-AGT-5.3 the story id check belongs to the requirements role")
    void checksTheRequirementsRole() {
        assertThat(check.roleId()).isEqualTo("requirements");
    }

    @Test
    @DisplayName("AC-AGT-5.3 stories with different ids pass the check")
    void acceptsDifferentIds() {
        assertThat(check.validate(mapper.readTree("{\"stories\":[{\"id\":\"S-1\"},{\"id\":\"S-2\"}]}"))).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-5.3 a repeated story id is named")
    void namesARepeatedId() {
        assertThat(check.validate(mapper.readTree("{\"stories\":[{\"id\":\"S-1\"},{\"id\":\"S-1\"}]}")))
                .containsExactly("Duplicate story id: S-1");
    }

    @Test
    @DisplayName("AC-AGT-5.3 stories without a usable id are left to the field checks")
    void skipsStoriesWithoutAUsableId() {
        assertThat(check.validate(mapper.readTree(
                "{\"stories\":[{\"title\":\"no id\"},{\"id\":5},{\"id\":\"  \"},{\"id\":\"S-1\"}]}"))).isEmpty();
    }
}
