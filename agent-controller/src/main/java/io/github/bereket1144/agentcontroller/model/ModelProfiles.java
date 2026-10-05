package io.github.bereket1144.agentcontroller.model;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The models the platform may call, and which one each role uses. This is the only way the rest of the platform
 * reads the {@code agent.models.*} configuration, which is checked before this object exists.
 */
@Component
@EnableConfigurationProperties(ModelProperties.class)
public class ModelProfiles {

    private static final Logger log = LoggerFactory.getLogger(ModelProfiles.class);

    private final ModelProperties properties;

    ModelProfiles(ModelProperties properties) {
        this.properties = properties;
        // Never add a key here: only names, model identifiers and windows are safe to log.
        log.info("event=model_profiles_loaded primary={} fallback={} profiles={}",
                primary().name(),
                fallback().map(ModelProfile::name).orElse("none"),
                properties.profiles().values().stream()
                        .map(profile -> profile.name() + "(model=" + profile.model()
                                + ",window=" + profile.contextWindow() + ")")
                        .collect(Collectors.joining(",")));
    }

    /** The profile every role uses unless configuration assigns it another one. */
    public ModelProfile primary() {
        return properties.profiles().get(properties.primary());
    }

    /** The profile to use when the primary is unavailable; empty when none is configured. */
    public Optional<ModelProfile> fallback() {
        return Optional.ofNullable(properties.fallback()).map(properties.profiles()::get);
    }

    /** The profile assigned to the role, or the primary when the role has none (any role name is accepted). */
    public ModelProfile forRole(String role) {
        Objects.requireNonNull(role, "role must not be null");
        String assigned = properties.roles().get(role);
        return assigned == null ? primary() : properties.profiles().get(assigned);
    }
}
