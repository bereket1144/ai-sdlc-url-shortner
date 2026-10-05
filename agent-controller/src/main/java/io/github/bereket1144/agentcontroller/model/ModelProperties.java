package io.github.bereket1144.agentcontroller.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The {@code agent.models.*} configuration, checked when it is read. Anything invalid stops the application at
 * startup with a message that names the property to fix; nothing invalid is ever handed on.
 *
 * @param profiles            the model profiles by name
 * @param primary             the name of the profile every role uses unless it is assigned another one
 * @param fallback            the name of the profile to use when the primary is unavailable, or null for none
 * @param roles               the profile name assigned to a role, by role name; the case of the role is ignored
 * @param safetyMarginPercent the part of a window, in percent, that a prompt and an answer must leave free
 */
@ConfigurationProperties("agent.models")
record ModelProperties(
        @DefaultValue Map<String, ModelProfile> profiles,
        String primary,
        String fallback,
        @DefaultValue Map<String, String> roles,
        @DefaultValue("10") int safetyMarginPercent) {

    private static final String PREFIX = "agent.models.";

    ModelProperties {
        require(safetyMarginPercent >= 0 && safetyMarginPercent < 100,
                PREFIX + "safety-margin-percent must be from 0 to 99 but was " + safetyMarginPercent);

        // Sorted, so that the first problem reported and the startup log do not depend on the order of the files.
        Map<String, ModelProfile> known = new TreeMap<>();
        profiles.forEach((name, profile) -> known.put(name, profile.named(name)));
        known.values().forEach(profile -> requireValid(profile, safetyMarginPercent));

        require(!isBlank(primary), PREFIX + "primary is required: name the profile that every role uses by default");
        requireKnown(known, "primary", primary);
        String checkedFallback = isBlank(fallback) ? null : fallback;
        if (checkedFallback != null) {
            requireKnown(known, "fallback", checkedFallback);
            require(!checkedFallback.equals(primary),
                    PREFIX + "fallback must differ from " + PREFIX + "primary, but both are '" + primary + "'");
        }

        // Environment variables cannot carry upper-case letters in a key, so AGENT_MODELS_ROLES_IMPACTANALYSIS
        // arrives as "impactanalysis". Ignoring the case lets it still find the role "impactAnalysis".
        Map<String, String> assigned = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        assigned.putAll(roles);
        assigned.forEach((role, profileName) -> requireKnown(known, "roles." + role, profileName));

        profiles = Collections.unmodifiableMap(known);
        fallback = checkedFallback;
        roles = Collections.unmodifiableMap(assigned);
    }

    private static void requireValid(ModelProfile profile, int safetyMarginPercent) {
        String path = PREFIX + "profiles." + profile.name() + ".";
        require(!isBlank(profile.model()), path + "model is required");
        require(profile.baseAddress() != null, path + "base-address is required");
        require(profile.contextWindow() > 0, path + "context-window must be set and greater than 0");
        require(profile.answerLimit() > 0, path + "answer-limit must be set and greater than 0");
        require(profile.callTimeout() != null && profile.callTimeout().isPositive(),
                path + "call-timeout must be set and greater than 0");
        require(profile.maxConcurrentCalls() > 0, path + "max-concurrent-calls must be greater than 0");
        require(profile.retryLimit() >= 0, path + "retry-limit must not be negative");

        long margin = (long) profile.contextWindow() * safetyMarginPercent / 100;
        require(profile.answerLimit() + margin < profile.contextWindow(),
                path + "answer-limit (" + profile.answerLimit() + ") plus the safety margin (" + margin
                        + " tokens, " + safetyMarginPercent + "% of the window) must be smaller than context-window ("
                        + profile.contextWindow() + "), or no room is left for a prompt");
    }

    private static void requireKnown(Map<String, ModelProfile> known, String field, String profileName) {
        require(known.containsKey(profileName),
                PREFIX + field + " names the unknown profile '" + profileName + "'; known profiles: "
                        + known.keySet());
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
