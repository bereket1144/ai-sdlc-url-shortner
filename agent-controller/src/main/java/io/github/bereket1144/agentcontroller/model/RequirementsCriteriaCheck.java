package io.github.bereket1144.agentcontroller.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Plain-code checks of the inside of a requirements answer: every story has criteria, ids are unique and begin with
 * their story's id, and no text is blank. Repeated story ids are left to {@link RequirementsStoryIdsCheck}. A message
 * names ids only, never a text, because messages go back to the model and into failure details.
 */
@Component
public class RequirementsCriteriaCheck implements OutputValidator.RoleCheck {

    @Override
    public String roleId() {
        return "requirements";
    }

    @Override
    public List<String> validate(JsonNode output) {
        List<String> messages = new ArrayList<>();
        // Union-shaped model responses sometimes include empty story fields during clarification.
        if (!output.has("stories") || (output.has("questions") && output.get("stories").isEmpty())) {
            if (!output.has("questions")) {
                return List.of("Clarification requires a questions array");
            }
            for (JsonNode question : output.get("questions")) {
                for (String textField : List.of("id", "text", "why")) {
                    if (text(question, textField) == null) {
                        messages.add("Clarification question has a blank " + textField);
                    }
                }
            }
            return messages;
        }
        if (!output.has("openQuestions")) {
            return List.of("Stories require an openQuestions array");
        }
        Set<String> criterionIds = new HashSet<>();
        for (JsonNode story : output.get("stories")) {
            checkStory(story, criterionIds, messages);
        }
        Set<String> questionIds = new HashSet<>();
        for (JsonNode question : output.get("openQuestions")) {
            String id = text(question, "id");
            if (id == null) {
                messages.add("An open question has a blank id");
            } else if (!questionIds.add(id)) {
                messages.add("Duplicate open question id: " + id);
            }
            for (String field : List.of("text", "why")) {
                if (text(question, field) == null) {
                    messages.add("Open question " + id + " has a blank " + field);
                }
            }
        }
        for (JsonNode assumption : output.get("assumptions")) {
            if (!assumption.isString() || assumption.asString().isBlank()) {
                messages.add("An assumption is blank");
            }
        }
        return messages;
    }

    private static void checkStory(JsonNode story, Set<String> criterionIds, List<String> messages) {
        String storyId = text(story, "id");
        if (storyId == null) {
            messages.add("A story has a blank id");
        }
        for (String field : List.of("title", "role", "want", "benefit")) {
            if (text(story, field) == null) {
                messages.add("Story " + storyId + " has a blank " + field);
            }
        }
        JsonNode criteria = story.get("acceptanceCriteria");
        if (criteria == null || !criteria.isArray() || criteria.size() == 0) {
            messages.add("Story " + storyId + " has no acceptance criterion");
            return;
        }
        for (JsonNode criterion : criteria) {
            String id = text(criterion, "id");
            if (id == null) {
                messages.add("Story " + storyId + " has a criterion with a blank id");
            } else {
                if (storyId != null && !id.startsWith(storyId)) {
                    messages.add("Criterion " + id + " does not start with its story id " + storyId);
                }
                if (!criterionIds.add(id)) {
                    messages.add("Duplicate criterion id: " + id);
                }
            }
            if (text(criterion, "text") == null) {
                messages.add("Criterion " + id + " has a blank text");
            }
        }
    }

    /** The text of a field, or null when the field is missing, not text, or blank. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
