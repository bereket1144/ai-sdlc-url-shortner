package io.github.bereket1144.agentcontroller.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.Prompt;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.modelcall.InputFingerprint;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.AttemptKind;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.Outcome;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.StoredAttempt;
import io.github.bereket1144.agentcontroller.modelcall.Recording;
import io.github.bereket1144.agentcontroller.modelcall.ReplayModelClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class ReplayedAnswerCheckTest {

    private static final String INVALID = "{\"stories\":\"wrong\",\"openQuestions\":[],\"assumptions\":[]}";
    private static final String VALID = "{\"stories\":[{\"id\":\"S-1\"}],\"openQuestions\":[],\"assumptions\":[]}";

    private final ModelProfile profile = new ModelProfile("local", "model", URI.create("http://localhost:11434"),
            8192, 512, Duration.ofSeconds(2), 1, 2, 0, null);

    @TempDir
    Path directory;

    @Test
    @DisplayName("AC-AGT-10.3 a recorded invalid answer is still invalid, so replay repairs it as a live call would")
    void aRecordedInvalidAnswerIsStillCheckedAndRepaired() throws IOException {
        RoleCatalog roles = new RoleCatalog(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/agent-roles/*.properties"));
        PromptBuilder prompts = new PromptBuilder(new ModelProfiles(new ModelProperties(Map.of("local", profile),
                "local", null, Map.of(), 10)), roles, 3);
        OutputValidator validator = new OutputValidator(roles, List.of(new RequirementsStoryIdsCheck()));
        List<PromptInput> inputs = List.of(new PromptInput(InputKind.ORIGINAL_REQUIREMENT, "v1",
                "Build a task tracker", null, false));
        // The recording holds what a live run would have sent and received: the first prompt, then its repair.
        Prompt first = prompts.build("requirements", profile, "Write stories", inputs);
        Prompt repair = prompts.repair("requirements", first, INVALID,
                validator.validate("requirements", INVALID).messages());
        Path file = directory.resolve("run.json");
        Recording.write(file, List.of(recorded(first, INVALID, AttemptKind.FIRST, Outcome.INVALID_ANSWER),
                recorded(repair, VALID, AttemptKind.REPAIR, Outcome.SUCCESS)));

        StructuredAnswerService service = new StructuredAnswerService(new ReplayModelClient(file.toString()),
                prompts, validator, 2);
        StructuredAnswerService.AcceptedAnswer accepted = service.call("requirements", profile, "Write stories",
                inputs);

        assertThat(accepted.repairAttempts()).isEqualTo(1);
        assertThat(accepted.value().get("stories").get(0).get("id").asString()).isEqualTo("S-1");
    }

    private StoredAttempt recorded(Prompt prompt, String answer, AttemptKind kind, Outcome outcome) {
        return new StoredAttempt(1, UUID.randomUUID(), "requirements", "1", profile.name(), profile.model(), kind,
                InputFingerprint.of(profile.name(), prompt.systemText(), prompt.userText()), prompt.systemText(),
                prompt.userText(), answer, FinishReason.COMPLETE, 100, 20L, null, null, "", 10, outcome);
    }
}
