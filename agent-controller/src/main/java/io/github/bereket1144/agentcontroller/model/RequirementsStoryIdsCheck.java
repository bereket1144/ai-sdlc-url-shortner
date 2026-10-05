package io.github.bereket1144.agentcontroller.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** A requirements answer cannot name the same story twice. */
@Component
public class RequirementsStoryIdsCheck implements OutputValidator.RoleCheck {

    @Override
    public String roleId() {
        return "requirements";
    }

    @Override
    public List<String> validate(JsonNode output) {
        Set<String> seen = new HashSet<>();
        List<String> messages = new ArrayList<>();
        for (JsonNode story : output.get("stories")) {
            JsonNode id = story.get("id");
            if (id != null && id.isString() && !id.asString().isBlank() && !seen.add(id.asString())) {
                messages.add("Duplicate story id: " + id.asString());
            }
        }
        return messages;
    }
}
