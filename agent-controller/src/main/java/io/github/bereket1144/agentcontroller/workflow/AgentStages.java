package io.github.bereket1144.agentcontroller.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final Map<String, AgentStageNode> nodes;

    /** Fails with the name of the setting when its value is neither {@code agent} nor {@code dummy}. */
    public AgentStages(Environment environment, RoleCatalog roles, StageInputs stageInputs,
                       StructuredAnswerService answers, ArtifactStore artifacts) {
        Map<String, AgentStageNode> enabled = new LinkedHashMap<>();
        for (String role : ROLES) {
            String setting = environment.getProperty("agent.stages." + role, "dummy");
            switch (setting) {
                case "agent" -> enabled.put(role, new AgentStageNode(role, roles, stageInputs, answers, artifacts));
                case "dummy" -> { }
                default -> throw new IllegalStateException(
                        "agent.stages." + role + " must be agent or dummy, not " + setting);
            }
        }
        this.nodes = Map.copyOf(enabled);
        log.info("event=agent_stages_enabled roles={}", enabled.keySet());
    }

    /** The agent node of each role that is switched on, by role id; empty when none is. */
    public Map<String, AgentStageNode> nodes() {
        return nodes;
    }
}
