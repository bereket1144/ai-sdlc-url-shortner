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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * The requirements stage run by the requirements agent. It exists only when {@code agent.stages.requirements=agent};
 * otherwise the dummy node stays the stage. It hands the next stage a reference to the stored artifact, never the text.
 */
@Component
@ConditionalOnProperty(name = "agent.stages.requirements", havingValue = "agent")
public class RequirementsAgentNode {

    private static final Logger log = LoggerFactory.getLogger(RequirementsAgentNode.class);
    private static final String ROLE = "requirements";
    private static final String CONTEXT = "Execution context: Python standard library, SQLite storage and pytest "
            + "are fixed for this version. Tests run offline in a container. Do not ask the user to choose these. ";

    private final StructuredAnswerService answers;
    private final ArtifactStore artifacts;
    private final ClarificationService clarification;

    public RequirementsAgentNode(StructuredAnswerService answers, ArtifactStore artifacts,
                                 ClarificationService clarification) {
        this.answers = answers;
        this.artifacts = artifacts;
        this.clarification = clarification;
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
        AcceptedAnswer batch = answers.call(runId, ROLE, CONTEXT
                + "CLARIFICATION phase: return only questions and assumptions arrays. Present all unresolved product "
                + "questions in one batch of at most four, ordered from most to least important. Ask only decisions "
                + "that materially affect the product. Put minor choices in optional assumptions with clear proposed "
                + "behaviour. Each item has id, text and why; questions may have proposedDefault. "
                + "Assumptions marked ASSUMED are usable proposals, not human-confirmed decisions; do not ask the user "
                + "to confirm them or repeat resolved decisions. Unanswered questions still require replies. "
                + "Do not produce stories. If nothing needs clarification, return both arrays empty.", inputs(requirement, history),
                value -> !value.has("questions") || hasItems(value, "stories") || hasItems(value, "openQuestions")
                        ? List.of("CLARIFICATION requires questions and assumptions, with no nonempty stories or openQuestions")
                        : List.of());
        clarification.store(runId, batch);
        if (!batch.value().get("questions").isEmpty()) {
            return Map.of("waitingForInput", true);
        }
        // Include proposals from this batch as well as decisions saved in previous rounds.
        history = clarification.questions(runId);
        AcceptedAnswer answer = answers.call(runId, ROLE, CONTEXT
                + "STORIES phase: generate user stories with numbered acceptance criteria using the original "
                + "requirement, all saved answers and optional assumptions. An answer that corrects an assumption "
                + "overrides its proposal. ASSUMED proposals can be used but are not human-confirmed. "
                + "Return stories, openQuestions:[] and assumptions:[]. Do not introduce unresolved decisions; "
                + "the controller attaches the assumptions with their confirmation status.", inputs(requirement, history),
                value -> !value.has("stories") || value.get("stories").isEmpty() || hasItems(value, "questions")
                        || !value.has("openQuestions") || !value.get("openQuestions").isEmpty()
                        || !value.get("assumptions").isEmpty()
                        ? List.of("STORIES requires nonempty stories and empty openQuestions and assumptions") : List.of());
        ObjectNode content = (ObjectNode) answer.value().deepCopy();
        content.remove("questions");
        ArrayNode assumptionsContent = content.putArray("assumptions");
        history.assumptions().forEach(item -> assumptionsContent.add(item.answer() == null
                ? item.text() + " (assumed; not confirmed by requester)"
                : item.text() + "; requester decision: " + item.answer()));
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
                new PromptInput(InputKind.QUESTIONS_AND_ANSWERS, "1",
                        JsonMapper.shared().writeValueAsString(history), null, false));
    }

    private static boolean hasItems(JsonNode value, String field) {
        return value.has(field) && !value.get(field).isEmpty();
    }

    private static <T> T required(AgentState state, String key) {
        return state.<T>value(key)
                .orElseThrow(() -> new IllegalStateException("Missing workflow state: " + key));
    }
}
