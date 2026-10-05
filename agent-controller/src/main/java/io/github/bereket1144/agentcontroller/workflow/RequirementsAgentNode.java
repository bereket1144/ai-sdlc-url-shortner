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
import io.github.bereket1144.agentcontroller.run.ClarificationService;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The requirements stage run by the requirements agent. It exists only when {@code agent.stages.requirements=agent};
 * otherwise the dummy node stays the stage. It hands the next stage a reference to the stored artifact, never the text.
 */
@Component
@ConditionalOnProperty(name = "agent.stages.requirements", havingValue = "agent")
public class RequirementsAgentNode {

    private static final Logger log = LoggerFactory.getLogger(RequirementsAgentNode.class);
    private static final String ROLE = "requirements";

    private final StructuredAnswerService answers;
    private final ArtifactStore artifacts;
    private final ClarificationService clarification;
    private final String context;

    /**
     * @param executionContext what the agent is told is fixed for the build, such as the language and the test tool.
     *                         It comes from {@code sandbox.verify.execution-context}, so that this class names none
     *                         of them; when it is left out the prompts carry no such sentence
     */
    public RequirementsAgentNode(StructuredAnswerService answers, ArtifactStore artifacts,
                                 ClarificationService clarification,
                                 @Value("${sandbox.verify.execution-context:}") String executionContext) {
        this.answers = answers;
        this.artifacts = artifacts;
        this.clarification = clarification;
        // Followed by a space, so that the text of a phase can be added to it.
        this.context = executionContext.isBlank() ? "" : executionContext.strip() + " ";
    }

    /** The graph consumes the final reference prepared before it starts, without another model call. */
    Map<String, Object> requirements(AgentState state) {
        return Map.of("requirements", required(state, "requirements"));
    }

    /** Resolve clarification before starting downstream work; a wait returns immediately to the dispatcher. */
    Map<String, Object> prepare(AgentState state) {
        UUID runId = required(state, "runId");
        String requirement = required(state, "requirement");
        if (clarification.awaiting(runId) != null) {
            return Map.of("waitingForInput", true);
        }
        ClarificationService.Questions history = clarification.questions(runId);
        // The questions are asked once, before the first stories. After the reply the run goes straight to the
        // stories, and a run with nothing to ask stores no clarification batch at all.
        if (history.questions().isEmpty()) {
            AcceptedAnswer batch = answers.call(runId, ROLE, context
                    + "CLARIFICATION phase: return a questions array and an empty assumptions array. Present all "
                    + "unresolved product questions in one batch of at most four, ordered from most to least "
                    + "important. Ask only decisions that materially affect the product, and do not ask the user to "
                    + "confirm assumptions. Each question has id, text and why, and may have proposedDefault. "
                    + "Do not produce stories. If nothing needs clarification, return questions as an empty array.",
                    inputs(requirement, history),
                    value -> !value.has("questions") || hasItems(value, "stories") || hasItems(value, "openQuestions")
                            || hasItems(value, "assumptions")
                            ? List.of("CLARIFICATION requires questions, with no nonempty stories, openQuestions "
                                    + "or assumptions")
                            : List.of());
            if (!batch.value().get("questions").isEmpty()) {
                clarification.store(runId, batch);
                return Map.of("waitingForInput", true);
            }
        }
        AcceptedAnswer answer = answers.call(runId, ROLE, context
                + "STORIES phase: generate user stories with numbered acceptance criteria using the original "
                + "requirement and all saved answers; an answer is the requester's decision. Return stories, "
                + "openQuestions:[] and assumptions. Write one short plain-text statement in assumptions for every "
                + "choice the requester did not decide and build the stories on it. Do not return questions.",
                inputs(requirement, history),
                value -> !value.has("stories") || value.get("stories").isEmpty() || hasItems(value, "questions")
                        || !value.has("openQuestions") || !value.get("openQuestions").isEmpty()
                        ? List.of("STORIES requires nonempty stories and an empty openQuestions") : List.of());
        ObjectNode content = (ObjectNode) answer.value().deepCopy();
        content.remove("questions");
        int stories = content.get("stories").size();
        int questions = content.get("openQuestions").size();
        int assumptions = content.get("assumptions").size();
        String summary = stories + " stories, " + questions + " open questions, " + assumptions + " assumptions";
        ArtifactRef ref = artifacts.store(new NewArtifact(runId, ROLE, content, summary, answer.profile(),
                clarification.references(runId)));
        // Ids, kind, version, fingerprint and counts only; the requirement, questions and answer are never logged.
        log.info("event=requirements_artifact_stored runId={} role={} kind={} version={} fingerprint={} "
                        + "stories={} openQuestions={} assumptions={}",
                runId, ROLE, ref.kind(), ref.version(), ref.fingerprint(), stories, questions, assumptions);
        return Map.of("requirements", ref);
    }

    private static List<PromptInput> inputs(String requirement, ClarificationService.Questions history) {
        return List.of(new PromptInput(InputKind.ORIGINAL_REQUIREMENT, "1", requirement, null, false),
                new PromptInput(InputKind.QUESTIONS_AND_ANSWERS, "1", questionsText(history), null, false));
    }

    /**
     * The questions and answers as the prompt shows them. The time of the reply and the batch version are left out:
     * they differ between runs with the same answers and would give each run its own prompt fingerprint, so a
     * recording of one run could never be replayed for another.
     */
    private static String questionsText(ClarificationService.Questions history) {
        ObjectNode copy = JsonMapper.shared().valueToTree(history);
        for (JsonNode item : copy.get("questions")) {
            ((ObjectNode) item).remove(List.of("answeredAt", "artifactVersion"));
        }
        return JsonMapper.shared().writeValueAsString(copy);
    }

    private static boolean hasItems(JsonNode value, String field) {
        return value.has(field) && !value.get(field).isEmpty();
    }

    private static <T> T required(AgentState state, String key) {
        return state.<T>value(key)
                .orElseThrow(() -> new IllegalStateException("Missing workflow state: " + key));
    }
}
