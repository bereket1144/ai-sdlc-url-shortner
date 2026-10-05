package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RequirementsCriteriaCheckTest {

    private final RequirementsCriteriaCheck check = new RequirementsCriteriaCheck();
    private final JsonMapper mapper = JsonMapper.shared();

    @Test
    @DisplayName("AC-AGT-12.2 a complete requirements answer passes the checks")
    void acceptsACompleteAnswer() {
        assertThat(check.validate(answer(story("S-1", "[" + criterion("S-1.1", "It works") + "]"),
                "[{\"id\":\"Q-1\",\"text\":\"Who?\",\"why\":\"Scope\"}]", "[\"Runs locally\"]"))).isEmpty();
    }

    @Test
    @DisplayName("AC-AGT-12.2 a story with no criterion is named")
    void namesAStoryWithoutACriterion() {
        assertThat(check.validate(answer(story("S-1", "[]"), "[]", "[]")))
                .containsExactly("Story S-1 has no acceptance criterion");
    }

    @Test
    @DisplayName("AC-AGT-12.2 a criterion id that does not start with its story id is named")
    void namesACriterionIdWithTheWrongPrefix() {
        assertThat(check.validate(answer(story("S-1", "[" + criterion("S-2.1", "It works") + "]"), "[]", "[]")))
                .containsExactly("Criterion S-2.1 does not start with its story id S-1");
    }

    @Test
    @DisplayName("AC-AGT-12.2 a repeated criterion id and a repeated question id are named")
    void namesRepeatedIds() {
        String criteria = "[" + criterion("S-1.1", "One") + "," + criterion("S-1.1", "Two") + "]";
        String question = "{\"id\":\"Q-1\",\"text\":\"A?\",\"why\":\"x\"}";
        String questions = "[" + question + "," + question + "]";

        assertThat(check.validate(answer(story("S-1", criteria), questions, "[]")))
                .containsExactly("Duplicate criterion id: S-1.1", "Duplicate open question id: Q-1");
    }

    @Test
    @DisplayName("AC-AGT-12.2 a blank text is named")
    void namesABlankText() {
        assertThat(check.validate(answer(story("S-1", "[" + criterion("S-1.1", " ") + "]"),
                "[{\"id\":\"Q-1\",\"text\":\"\",\"why\":\"x\"}]", "[\" \"]")))
                .containsExactly("Criterion S-1.1 has a blank text", "Open question Q-1 has a blank text",
                        "An assumption is blank");
    }

    @Test
    @DisplayName("AC-AGT-12.2 a blank id or field, a missing criteria list and a non-text assumption are named")
    void namesBlankIdsAndFieldsAndMissingParts() {
        String blankIdAndTitle = "{\"id\":\"\",\"title\":\" \",\"role\":\"user\",\"want\":\"w\",\"benefit\":\"b\","
                + "\"acceptanceCriteria\":[{\"id\":\"\",\"text\":\"x\"}]}";
        String blankIdOnly = "{\"id\":\"\",\"title\":\"T\",\"role\":\"user\",\"want\":\"w\",\"benefit\":\"b\","
                + "\"acceptanceCriteria\":[{\"id\":\"X.1\",\"text\":\"x\"}]}";
        String noCriteriaKey = "{\"id\":\"S-3\",\"title\":\"T\",\"role\":\"user\",\"want\":\"w\",\"benefit\":\"b\"}";

        assertThat(check.validate(answer(blankIdAndTitle + "," + blankIdOnly + "," + noCriteriaKey,
                "[{\"id\":\" \",\"text\":\"A\",\"why\":\"\"}]", "[1]")))
                .containsExactly("A story has a blank id", "Story null has a blank title",
                        "Story null has a criterion with a blank id", "A story has a blank id",
                        "Story S-3 has no acceptance criterion", "An open question has a blank id",
                        "Open question null has a blank why", "An assumption is blank");
    }

    private static String story(String id, String criteria) {
        return "{\"id\":\"" + id + "\",\"title\":\"T\",\"role\":\"user\",\"want\":\"w\",\"benefit\":\"b\","
                + "\"acceptanceCriteria\":" + criteria + "}";
    }

    private static String criterion(String id, String text) {
        return "{\"id\":\"" + id + "\",\"text\":\"" + text + "\"}";
    }

    private JsonNode answer(String story, String questions, String assumptions) {
        return mapper.readTree("{\"stories\":[" + story + "],\"openQuestions\":" + questions
                + ",\"assumptions\":" + assumptions + "}");
    }
}
