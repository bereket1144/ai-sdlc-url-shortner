package io.github.bereket1144.agentcontroller.workspace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.bereket1144.agentcontroller.model.OutputValidator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Plain-code checks of the change set in a development answer: it is not empty, and every file has a path that may be
 * written and a content that is not too large. A message names the number of the change and the rule, never a path or
 * a content, because messages go back to the model and into failure details. A link cannot be seen in an answer; the
 * workspace refuses to write through one.
 */
@Component
class ChangeSetCheck implements OutputValidator.RoleCheck {

    @Override
    public String roleId() {
        return "development";
    }

    @Override
    public List<String> validate(JsonNode output) {
        JsonNode changes = output.get("changes");
        if (changes.size() == 0) {
            return List.of("The change set is empty: list at least one file");
        }
        List<String> messages = new ArrayList<>();
        int number = 0;
        for (JsonNode change : changes) {
            number++;
            JsonNode path = change.get("path");
            if (path == null || !path.isString()) {
                messages.add("Change " + number + " has no path text");
            } else {
                String problem = ChangeSet.pathProblem(path.asString());
                if (problem != null) {
                    messages.add("Change " + number + " " + problem);
                }
            }
            JsonNode content = change.get("content");
            if (content == null || !content.isString()) {
                messages.add("Change " + number + " has no content text");
            } else if (content.asString().getBytes(StandardCharsets.UTF_8).length > ChangeSet.MAX_FILE_BYTES) {
                messages.add("Change " + number + " is larger than " + ChangeSet.MAX_FILE_BYTES + " bytes");
            }
        }
        return messages;
    }
}
