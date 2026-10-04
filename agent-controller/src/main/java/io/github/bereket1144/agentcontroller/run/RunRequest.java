package io.github.bereket1144.agentcontroller.run;

import jakarta.validation.constraints.NotBlank;

public record RunRequest(@NotBlank String requirement) {
}
