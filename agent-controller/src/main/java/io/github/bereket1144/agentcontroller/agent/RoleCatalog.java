package io.github.bereket1144.agentcontroller.agent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Loads the instructions for the eight SDLC roles before any run can start. */
@Component
public class RoleCatalog {

    private static final Logger log = LoggerFactory.getLogger(RoleCatalog.class);
    private static final Set<String> ROLE_IDS = Set.of("requirements", "impactAnalysis", "design",
            "planning", "development", "qa", "review", "documentation");

    private final Map<String, RoleDefinition> roles;

    public RoleCatalog(@Value("classpath*:/agent-roles/*.properties") Resource[] resources) {
        Objects.requireNonNull(resources, "role resources must not be null");
        Map<String, RoleDefinition> loaded = new HashMap<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(".properties")) {
                throw new IllegalStateException("Invalid role resource: " + resource);
            }
            String roleId = filename.substring(0, filename.length() - ".properties".length());
            if (!ROLE_IDS.contains(roleId)) {
                throw new IllegalStateException("Unknown role: " + roleId);
            }
            RoleDefinition definition = load(roleId, resource);
            if (loaded.putIfAbsent(roleId, definition) != null) {
                throw new IllegalStateException("Duplicate role: " + roleId);
            }
        }
        for (String roleId : ROLE_IDS) {
            if (!loaded.containsKey(roleId)) {
                throw new IllegalStateException("Missing role: " + roleId);
            }
        }
        Map<String, String> identities = new HashMap<>();
        for (RoleDefinition definition : loaded.values()) {
            String previous = identities.putIfAbsent(definition.identity(), definition.id());
            if (previous != null) {
                throw new IllegalStateException("Roles " + previous + " and " + definition.id()
                        + " share the same identity");
            }
        }
        roles = Map.copyOf(loaded);
        log.info("event=agent_roles_loaded count={}", roles.size());
    }

    public RoleDefinition get(String roleId) {
        RoleDefinition definition = roles.get(roleId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown role: " + roleId);
        }
        return definition;
    }

    public Collection<RoleDefinition> all() {
        return roles.values();
    }

    private static RoleDefinition load(String roleId, Resource resource) {
        Properties properties = new Properties();
        try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot read role " + roleId + " from " + resource, exception);
        }

        List<String> standards = split(roleId, "standards", required(roleId, properties, "standards"), ";");
        if (standards.size() < 3 || standards.stream().distinct().count() < 3) {
            throw new IllegalStateException("Role " + roleId + " needs at least three named standards");
        }
        List<InputKind> inputs = split(roleId, "inputs", required(roleId, properties, "inputs"), ",")
                .stream().map(input -> parseInput(roleId, input)).toList();
        if (inputs.stream().distinct().count() != inputs.size()) {
            throw new IllegalStateException("Role " + roleId + " has duplicate inputs");
        }
        String approval = required(roleId, properties, "output.approvalRequired");
        if (!approval.equals("true") && !approval.equals("false")) {
            throw new IllegalStateException("Role " + roleId + " has invalid output.approvalRequired: " + approval);
        }
        return new RoleDefinition(roleId,
                required(roleId, properties, "identity"),
                required(roleId, properties, "mission"),
                required(roleId, properties, "voice"),
                standards,
                inputs,
                new Output(required(roleId, properties, "output.kind"),
                        required(roleId, properties, "output.structure"), Boolean.parseBoolean(approval)),
                required(roleId, properties, "boundaries"),
                required(roleId, properties, "uncertainty"),
                required(roleId, properties, "selfCheck"),
                required(roleId, properties, "version"));
    }

    private static String required(String roleId, Properties properties, String field) {
        String value = properties.getProperty(field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Role " + roleId + " is missing " + field);
        }
        return value.strip();
    }

    private static List<String> split(String roleId, String field, String value, String separator) {
        List<String> parts = Arrays.stream(value.split(separator, -1)).map(String::strip).toList();
        if (parts.stream().anyMatch(String::isBlank)) {
            throw new IllegalStateException("Role " + roleId + " has a blank " + field + " entry");
        }
        return parts;
    }

    private static InputKind parseInput(String roleId, String input) {
        try {
            return InputKind.valueOf(input);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Role " + roleId + " has unknown input kind: " + input, exception);
        }
    }

    public enum InputKind {
        ORIGINAL_REQUIREMENT,
        QUESTIONS_AND_ANSWERS,
        SELECTED_WORKSPACE_FILES,
        REQUIREMENTS_ARTIFACT,
        IMPACT_ANALYSIS_ARTIFACT,
        DESIGN_ARTIFACT,
        PLAN_ARTIFACT,
        DEVELOPMENT_ARTIFACT,
        QA_ARTIFACT,
        REVIEW_ARTIFACT,
        DOCUMENTATION_ARTIFACT
    }

    public record Output(String kind, String structure, boolean approvalRequired) { }

    public record RoleDefinition(String id, String identity, String mission, String voice,
                                 List<String> standards, List<InputKind> inputs, Output output,
                                 String boundaries, String uncertainty, String selfCheck, String version) { }
}
