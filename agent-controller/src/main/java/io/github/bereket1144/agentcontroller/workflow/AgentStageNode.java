package io.github.bereket1144.agentcontroller.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.FieldType;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;
import io.github.bereket1144.agentcontroller.agent.RoleCatalog.RoleDefinition;
import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.NewArtifact;
import io.github.bereket1144.agentcontroller.artifact.StageInputs;
import io.github.bereket1144.agentcontroller.artifact.StageInputs.Resolved;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.AcceptedAnswer;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One SDLC stage run by its agent, for any role. It reads the artifacts that earlier stages left in graph state and
 * that its role may read, asks the role through the structured answer service, stores one artifact and hands the next
 * stage only its reference. It does not know what an artifact means, so the development role is stored like every
 * other. {@link AgentStages} decides which roles get one.
 */
public class AgentStageNode {

    private static final Logger log = LoggerFactory.getLogger(AgentStageNode.class);
    static final String TASK = "Do the work of your role for this run, using only the inputs below and "
            + "the output structure of your role. State every assumption you make.";
    /** The graph state key under which a stage leaves its artifact reference, by the input kind that names it. */
    private static final Map<InputKind, String> STATE_KEY = Map.of(
            InputKind.REQUIREMENTS_ARTIFACT, "requirements",
            InputKind.IMPACT_ANALYSIS_ARTIFACT, "impactAnalysis",
            InputKind.DESIGN_ARTIFACT, "design",
            InputKind.PLAN_ARTIFACT, "planning",
            InputKind.DEVELOPMENT_ARTIFACT, "development",
            InputKind.DEVELOPMENT_OUTLINE, "development",
            InputKind.QA_ARTIFACT, "qa",
            InputKind.REVIEW_ARTIFACT, "review",
            InputKind.DOCUMENTATION_ARTIFACT, "documentation");

    private final String role;
    private final RoleDefinition definition;
    private final StageInputs stageInputs;
    private final StructuredAnswerService answers;
    private final ArtifactStore artifacts;
    private final String task;

    /** Fails with an unknown-role error when the catalog has no such role, so a wrong setting stops the start. */
    public AgentStageNode(String role, RoleCatalog roles, StageInputs stageInputs, StructuredAnswerService answers,
                          ArtifactStore artifacts) {
        this(role, roles, stageInputs, answers, artifacts, TASK);
    }

    /** As above, with a task text of its own, for a role that needs more than the plain task. */
    public AgentStageNode(String role, RoleCatalog roles, StageInputs stageInputs, StructuredAnswerService answers,
                          ArtifactStore artifacts, String task) {
        this.task = task;
        this.role = role;
        this.definition = roles.get(role);
        this.stageInputs = stageInputs;
        this.answers = answers;
        this.artifacts = artifacts;
    }

    Map<String, Object> execute(AgentState state) {
        UUID runId = required(state, "runId");
        List<PromptInput> inputs = new ArrayList<>();
        if (definition.inputs().contains(InputKind.ORIGINAL_REQUIREMENT)) {
            String requirement = required(state, "requirement");
            inputs.add(new PromptInput(InputKind.ORIGINAL_REQUIREMENT, "1", requirement, null, false));
        }
        Resolved resolved = stageInputs.resolve(runId, role, readableRefs(state));
        inputs.addAll(resolved.inputs());
        // A failed call or an answer that stays invalid throws a typed exception; nothing is stored then.
        AcceptedAnswer answer = answers.call(runId, role, task, inputs);
        JsonNode content = withQuestionsAsAssumptions(answer.value());
        ArtifactRef ref = artifacts.store(new NewArtifact(runId, role, content, summary(content), answer.profile(),
                resolved.consumed()));
        // Ids, kind, version, fingerprint and counts only; the requirement and the answer are never logged.
        log.info("event=agent_stage_artifact_stored runId={} role={} kind={} version={} fingerprint={} "
                        + "consumedCount={} openQuestions={}",
                runId, role, ref.kind(), ref.version(), ref.fingerprint(), resolved.consumed().size(),
                content.path("openQuestions").size());
        return Map.of(role, ref);
    }

    /**
     * In this first run an open question does not pause the run. It stays in the answer, and an assumption that it
     * was left unanswered is added to the assumptions, so the stages after it know what was taken as given. An answer
     * without open questions is returned as it is.
     */
    static JsonNode withQuestionsAsAssumptions(JsonNode answer) {
        JsonNode questions = answer.path("openQuestions");
        if (!questions.isArray() || questions.isEmpty()) {
            return answer;
        }
        // A validated answer is always a JSON object; copy it so the caller's answer is not changed.
        ObjectNode content = (ObjectNode) answer.deepCopy();
        ArrayNode assumptions = content.withArrayProperty("assumptions");
        for (JsonNode question : questions) {
            assumptions.add("Unanswered question: " + questionText(question));
        }
        return content;
    }

    /** A question is a text or an object with a {@code text}; any other shape is kept as its JSON. */
    private static String questionText(JsonNode question) {
        JsonNode text = question.isObject() ? question.path("text") : question;
        return text.isString() ? text.asString() : question.toString();
    }

    /**
     * The references in graph state of the kinds this role lists, in the order it lists them. A stage that ran on its
     * dummy node left a marker text and no reference, and a stage that did not run left nothing: neither is read.
     */
    private List<ArtifactRef> readableRefs(AgentState state) {
        List<ArtifactRef> refs = new ArrayList<>();
        for (InputKind kind : definition.inputs()) {
            String key = STATE_KEY.get(kind);
            if (key != null) {
                state.<Object>value(key).filter(ArtifactRef.class::isInstance).map(ArtifactRef.class::cast)
                        .ifPresent(refs::add);
            }
        }
        return refs;
    }

    /** The size of each list in the answer, like "tasks 5, assumptions 1": counts only, never any of the text. */
    private String summary(JsonNode content) {
        return definition.output().fields().stream()
                .filter(field -> field.type() == FieldType.ARRAY && content.has(field.name()))
                .map(field -> field.name() + " " + content.get(field.name()).size())
                .collect(Collectors.joining(", "));
    }

    private static <T> T required(AgentState state, String key) {
        return state.<T>value(key)
                .orElseThrow(() -> new IllegalStateException("Missing workflow state: " + key));
    }
}
