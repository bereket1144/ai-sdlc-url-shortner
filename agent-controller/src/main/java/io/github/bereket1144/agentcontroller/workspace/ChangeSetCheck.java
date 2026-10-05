package io.github.bereket1144.agentcontroller.workspace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.bereket1144.agentcontroller.model.OutputValidator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Plain-code checks of the change set in a development answer: it is not empty, every change has a path that may be
 * written and either a content that is not too large or {@code "delete": true} and no content, and no two changes
 * clash: the same path twice, or a file at the path that another file needs as a folder. A change set that would only
 * fail when it is written is refused here, so that it goes back to the model for a repair. A message names the number
 * of the change and the rule, never a path or a content, because messages go back to the model and into failure
 * details. A link, and a folder where a file is to be deleted, cannot be seen in an answer; the workspace refuses
 * those.
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
        // Both are keyed by the path in lower case, as a file system that ignores case would see it.
        Map<String, Integer> filePaths = new HashMap<>();
        Map<String, Integer> folderPaths = new HashMap<>();
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
                } else {
                    String clash = clash(number, ChangeSet.parts(path.asString()), filePaths, folderPaths);
                    if (clash != null) {
                        messages.add(clash);
                    }
                }
            }
            String bodyProblem = bodyProblem(change);
            if (bodyProblem != null) {
                messages.add("Change " + number + " " + bodyProblem);
            }
        }
        return messages;
    }

    /**
     * What is wrong with what a change does to its file, as a phrase that follows "Change N", or null when it is a
     * sound write (a content that is text and not too large) or a sound deletion ({@code "delete": true}, no content,
     * and a path that does not end in a slash, which would name a folder).
     */
    private static String bodyProblem(JsonNode change) {
        JsonNode delete = change.get("delete");
        JsonNode content = change.get("content");
        if (delete == null) {
            if (content == null || !content.isString()) {
                return "has no content text";
            }
            return content.asString().getBytes(StandardCharsets.UTF_8).length > ChangeSet.MAX_FILE_BYTES
                    ? "is larger than " + ChangeSet.MAX_FILE_BYTES + " bytes" : null;
        }
        if (!delete.isBoolean() || !delete.asBoolean()) {
            return "has a delete that is not true";
        }
        if (content != null) {
            return "has both content and delete";
        }
        boolean folder = change.path("path").asString("").endsWith("/");
        return folder ? "deletes a path that ends in a slash, and only files can be deleted" : null;
    }

    /**
     * The message for a change whose path writes or deletes the same file as an earlier change, or a file where an
     * earlier change needs a folder, or a folder where an earlier change writes a file; null when it fits. It also
     * records the path. A file that is deleted is recorded like one that is written, so a path is used once.
     *
     * @param filePaths   the path of each file written or deleted so far, by its path in lower case, with the number
     *                    of its change
     * @param folderPaths the folders that the files so far need, by their path in lower case, with the number of the
     *                    first change that needs each
     */
    private static String clash(int number, List<String> parts, Map<String, Integer> filePaths,
                                Map<String, Integer> folderPaths) {
        String file = String.join("/", parts).toLowerCase(Locale.ROOT);
        Integer same = filePaths.putIfAbsent(file, number);
        if (same != null) {
            return "Change " + number + " repeats the path of change " + same;
        }
        Integer needsFolder = folderPaths.get(file);
        if (needsFolder != null) {
            return "Change " + number + " writes a file where change " + needsFolder + " needs a folder";
        }
        String message = null;
        StringBuilder folder = new StringBuilder();
        for (String part : parts.subList(0, parts.size() - 1)) {
            folder.append(folder.isEmpty() ? "" : "/").append(part.toLowerCase(Locale.ROOT));
            Integer writesFile = filePaths.get(folder.toString());
            if (writesFile != null && message == null) {
                message = "Change " + number + " needs a folder where change " + writesFile + " writes a file";
            }
            folderPaths.putIfAbsent(folder.toString(), number);
        }
        return message;
    }
}
