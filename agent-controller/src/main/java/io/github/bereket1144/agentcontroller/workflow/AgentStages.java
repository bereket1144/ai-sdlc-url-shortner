package io.github.bereket1144.agentcontroller.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.StageInputs;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The stages that run on their agent: the roles whose setting {@code agent.stages.<role>} is {@code agent}. Every
 * other stage keeps its dummy node, which is the default, so a run with no setting needs no model. The requirements
 * stage is switched by its own {@link RequirementsAgentNode}. This class exists because one annotation on one class
 * cannot switch a node per role.
 */
@Component
public class AgentStages {

    private static final Logger log = LoggerFactory.getLogger(AgentStages.class);
    private static final List<String> ROLES = List.of("impactAnalysis", "design", "planning", "development",
            "qa", "review", "documentation");
    /** The roles that are shown the existing code when a run changes an earlier run. */
    private static final Set<String> CHANGE_ROLES = Set.of("impactAnalysis", "design", "planning", "development");

    private final Map<String, AgentStageNode> nodes;

    /** Fails with the name of the setting when its value is neither {@code agent} nor {@code dummy}. */
    public AgentStages(Environment environment, RoleCatalog roles, StageInputs stageInputs,
                       StructuredAnswerService answers, ArtifactStore artifacts, Optional<ExistingFiles> existingFiles) {
        Map<String, AgentStageNode> enabled = new LinkedHashMap<>();
        // Left out when the sandbox needs no hint; then the development agent is given the task text alone.
        String fileLayout = environment.getProperty("sandbox.verify.file-layout", "").strip();
        for (String role : ROLES) {
            String setting = environment.getProperty("agent.stages." + role, "dummy");
            switch (setting) {
                case "agent" -> enabled.put(role, node(role, fileLayout, roles, stageInputs, answers, artifacts)
                        .showingExistingFiles(CHANGE_ROLES.contains(role) ? existingFiles : Optional.empty()));
                case "dummy" -> { }
                default -> throw new IllegalStateException(
                        "agent.stages." + role + " must be agent or dummy, not " + setting);
            }
        }
        this.nodes = Map.copyOf(enabled);
        log.info("event=agent_stages_enabled roles={}", enabled.keySet());
    }

    /**
     * The development role is told what layout its files take, as {@code sandbox.verify.file-layout} says, so that
     * this class names no language or test tool; every other role gets the plain task text.
     */
    private static AgentStageNode node(String role, String fileLayout, RoleCatalog roles, StageInputs stageInputs,
                                       StructuredAnswerService answers, ArtifactStore artifacts) {
        if (!role.equals("development")) {
            return new AgentStageNode(role, roles, stageInputs, answers, artifacts);
        }
        return new AgentStageNode(role, roles, stageInputs, answers, artifacts, AgentStageNode.TASK
                + (fileLayout.isEmpty() ? "" : " " + fileLayout)
                + " The content of each file must be the complete file, ready to run: no placeholders, no ellipses and"
                + " no comments such as \"rest of the code\". Include the tests as files in the same change set.");
    }

    /** The agent node of each role that is switched on, by role id; empty when none is. */
    public Map<String, AgentStageNode> nodes() {
        return nodes;
    }
}
