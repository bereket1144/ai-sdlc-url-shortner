package io.github.bereket1144.agentcontroller.sandbox;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code sandbox.verify.*} configuration: which image builds and tests a workspace, with which command, and
 * the limits the container keeps. The tool and the language are named here only, in configuration, never in code.
 * A missing or invalid value stops the application at startup with a message that names the property.
 *
 * @param image      the image the container is created from; it is built outside this application
 * @param command    the shell command line the container runs. The workspace is at {@code /workspace}; the command
 *                   must write a JUnit XML report to {@code reportPath}
 * @param reportPath where, inside the container, the command writes its report; only this file is copied out
 * @param memory     the memory limit, in the notation of {@code docker create --memory}
 * @param cpus       the most CPUs the container may use
 * @param pidsLimit  the most processes the container may run
 * @param timeout    how long the command may run before the container is killed
 */
@Validated
@ConfigurationProperties("sandbox.verify")
record SandboxProperties(
        @NotBlank String image,
        @NotBlank String command,
        @DefaultValue("/tmp/junit.xml") @NotBlank String reportPath,
        @DefaultValue("1g") @NotBlank String memory,
        @DefaultValue("1") @Positive double cpus,
        @DefaultValue("256") @Positive int pidsLimit,
        @DefaultValue("5m") @DurationMin(millis = 1) Duration timeout) { }
