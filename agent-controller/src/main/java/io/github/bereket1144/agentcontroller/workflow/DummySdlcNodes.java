package io.github.bereket1144.agentcontroller.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.bereket1144.agentcontroller.run.RunKind;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Deterministic stage outputs that make graph routing and state handoff visible. */
@Component
public class DummySdlcNodes {

    private static final Logger log = LoggerFactory.getLogger(DummySdlcNodes.class);
    private static final List<String> ROLE_KEYS = List.of("requirements", "impactAnalysis", "design",
            "planning", "development", "qa", "review", "documentation");

    Map<String, Object> initialize(AgentState state) {
        UUID runId = required(state, "runId");
        String requirement = required(state, "requirement");
        return output(state, "initialize", Map.of("receipt", runId + ":" + requirement));
    }

    Map<String, Object> requirements(AgentState state) {
        required(state, "receipt");
        return marker(state, "requirements");
    }

    Map<String, Object> impactAnalysis(AgentState state) {
        required(state, "requirements");
        return marker(state, "impactAnalysis");
    }

    Map<String, Object> design(AgentState state) {
        required(state, "requirements");
        RunKind kind = required(state, "kind");
        if (kind == RunKind.BROWNFIELD) {
            required(state, "impactAnalysis");
        }
        return marker(state, "design");
    }

    Map<String, Object> planning(AgentState state) {
        required(state, "design");
        return marker(state, "planning");
    }

    Map<String, Object> development(AgentState state) {
        required(state, "planning");
        return marker(state, "development");
    }

    Map<String, Object> qa(AgentState state) {
        required(state, "development");
        return marker(state, "qa");
    }

    Map<String, Object> review(AgentState state) {
        required(state, "development");
        return marker(state, "review");
    }

    Map<String, Object> documentation(AgentState state) {
        required(state, "development");
        return marker(state, "documentation");
    }

    Map<String, Object> collect(AgentState state) {
        required(state, "qa");
        required(state, "review");
        required(state, "documentation");
        return output(state, "collect", Map.of("joined", true));
    }

    private static Map<String, Object> marker(AgentState state, String role) {
        required(state, "requirement");
        return output(state, role, Map.of(role, role + ":done"));
    }

    private static Map<String, Object> output(AgentState state, String node, Map<String, Object> update) {
        UUID runId = required(state, "runId");
        Map<String, String> visibleMarkers = new LinkedHashMap<>();
        // Show only fixed dummy markers. The requirement and receipt can contain private user text.
        for (String key : ROLE_KEYS) {
            state.<String>value(key).ifPresent(value -> visibleMarkers.put(key, value));
            if (update.containsKey(key)) {
                visibleMarkers.put(key, (String) update.get(key));
            }
        }
        log.info("event=workflow_node_output runId={} node={} addedKeys={} markers={}",
                runId, node, update.keySet(), visibleMarkers);
        return update;
    }

    private static <T> T required(AgentState state, String key) {
        return state.<T>value(key)
                .orElseThrow(() -> new IllegalStateException("Missing workflow state: " + key));
    }
}
