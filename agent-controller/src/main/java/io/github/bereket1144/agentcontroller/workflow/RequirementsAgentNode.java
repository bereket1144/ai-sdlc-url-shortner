package io.github.bereket1144.agentcontroller.workflow;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.NewArtifact;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.AcceptedAnswer;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The requirements stage run by the requirements agent. It exists only when {@code agent.stages.requirements=agent};
 * otherwise the dummy node stays the stage. It hands the next stage a reference to the stored artifact, never the text.
 */
@Component
@ConditionalOnProperty(name = "agent.stages.requirements", havingValue = "agent")
public class RequirementsAgentNode {

    private static final Logger log = LoggerFactory.getLogger(RequirementsAgentNode.class);
    private static final String ROLE = "requirements";
    private static final String TASK = "Turn the original requirement into user stories with numbered acceptance "
            + "criteria. State every assumption you make. Ask an open question only where the answer would change "
            + "the scope.";

    private final StructuredAnswerService answers;
    private final ArtifactStore artifacts;

    public RequirementsAgentNode(StructuredAnswerService answers, ArtifactStore artifacts) {
        this.answers = answers;
        this.artifacts = artifacts;
    }

    Map<String, Object> requirements(AgentState state) {
        UUID runId = required(state, "runId");
        String requirement = required(state, "requirement");
        // A failed call or an answer that stays invalid throws a typed exception; nothing is stored then.
        AcceptedAnswer answer = answers.call(runId, ROLE, TASK, List.of(
                new PromptInput(InputKind.ORIGINAL_REQUIREMENT, "1", requirement, null, false)));
        // An open question does not pause the run yet; it is also listed as an assumption (AC-AGT-62.4).
        JsonNode content = AgentStageNode.withQuestionsAsAssumptions(answer.value());
        int stories = content.get("stories").size();
        int questions = content.get("openQuestions").size();
        int assumptions = content.get("assumptions").size();
        String summary = stories + " stories, " + questions + " open questions, " + assumptions + " assumptions";
        ArtifactRef ref = artifacts.store(new NewArtifact(runId, ROLE, content, summary, answer.profile(), List.of()));
        // Ids, kind, version, fingerprint and counts only; the requirement, questions and answer are never logged.
        log.info("event=requirements_artifact_stored runId={} role={} kind={} version={} fingerprint={} "
                        + "stories={} openQuestions={} assumptions={}",
                runId, ROLE, ref.kind(), ref.version(), ref.fingerprint(), stories, questions, assumptions);
        return Map.of("requirements", ref);
    }

    private static <T> T required(AgentState state, String key) {
        return state.<T>value(key)
                .orElseThrow(() -> new IllegalStateException("Missing workflow state: " + key));
    }
}
