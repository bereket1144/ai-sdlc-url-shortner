package io.github.bereket1144.agentcontroller.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.OutputField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Checks a model answer against the role's declared output fields without trusting its prose. */
@Component
public class OutputValidator {

    private static final Logger log = LoggerFactory.getLogger(OutputValidator.class);

    private final RoleCatalog roles;
    private final List<RoleCheck> checks;
    private final JsonMapper mapper = JsonMapper.shared();

    public OutputValidator(RoleCatalog roles, List<RoleCheck> checks) {
        this.roles = roles;
        this.checks = List.copyOf(checks);
    }

    public Validation validate(String roleId, String answer) {
        List<OutputField> fields = roles.get(roleId).output().fields();
        Objects.requireNonNull(answer, "answer must not be null");
        List<String> messages = new ArrayList<>();
        JsonNode object = extractObject(answer, messages);
        if (object == null) {
            log.info("event=model_output_checked role={} valid=false issueCount={}", roleId, messages.size());
            return new Validation(null, messages);
        }

        ObjectNode accepted = mapper.createObjectNode();
        for (OutputField field : fields) {
            JsonNode value = object.get(field.name());
            if (value == null) {
                if (field.required()) {
                    messages.add("Missing required field: " + field.name());
                }
                continue;
            }
            if (value.isNull() || (field.required() && value.isString() && value.asString().isBlank())) {
                messages.add("Blank field: " + field.name());
            } else if (!matchesType(value, field)) {
                messages.add("Wrong type for field " + field.name() + ": expected " + field.type());
            } else {
                // Keep only declared fields, so extra model output never becomes an artifact.
                accepted.set(field.name(), value.deepCopy());
            }
        }
        if (messages.isEmpty()) {
            for (RoleCheck check : checks) {
                if (check.roleId().equals(roleId)) {
                    messages.addAll(check.validate(accepted));
                }
            }
        }
        boolean valid = messages.isEmpty();
        log.info("event=model_output_checked role={} valid={} issueCount={}", roleId, valid, messages.size());
        return new Validation(valid ? accepted : null, messages);
    }

    private JsonNode extractObject(String answer, List<String> messages) {
        JsonNode found = null;
        int start = -1;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        boolean malformed = false;
        for (int index = 0; index < answer.length(); index++) {
            char current = answer.charAt(index);
            if (depth == 0) {
                if (current == '{') {
                    start = index;
                    depth = 1;
                }
                continue;
            }
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
            } else if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        JsonNode candidate = mapper.readTree(answer.substring(start, index + 1));
                        // Text that starts with { and parses is always an object.
                        if (found != null) {
                            messages.add("Answer contains more than one JSON object");
                        } else {
                            found = candidate;
                        }
                    } catch (JacksonException exception) {
                        malformed = true;
                    }
                }
            }
        }
        if (depth != 0) {
            messages.add("Incomplete JSON object");
        }
        if (found == null && messages.isEmpty()) {
            messages.add(malformed ? "Malformed JSON object" : "Answer contains no JSON object");
        }
        return messages.isEmpty() ? found : null;
    }

    private static boolean matchesType(JsonNode value, OutputField field) {
        return switch (field.type()) {
            case STRING -> value.isString();
            case NUMBER -> value.isNumber();
            case BOOLEAN -> value.isBoolean();
            case OBJECT -> value.isObject();
            case ARRAY -> value.isArray();
        };
    }

    public record Validation(JsonNode value, List<String> messages) {
        public Validation {
            messages = List.copyOf(messages);
        }

        public boolean valid() {
            return messages.isEmpty();
        }
    }

    /** A role may add semantic checks after its JSON fields have passed the shared schema. */
    public interface RoleCheck {
        String roleId();

        List<String> validate(JsonNode output);
    }
}
