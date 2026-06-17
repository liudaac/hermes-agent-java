package com.nousresearch.hermes.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Foundation input projection from a Business Portal Scenario to IntentOrchestrator. */
public record ScenarioIntentRequest(
    String workspaceId,
    String scenarioId,
    String intent,
    String preferredTeamId,
    boolean allowDelegation,
    List<String> contextSignals,
    Map<String, Object> metadata
) {
    public ScenarioIntentRequest {
        contextSignals = contextSignals != null ? List.copyOf(contextSignals) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workspaceId", workspaceId);
        map.put("scenarioId", scenarioId);
        map.put("intent", intent);
        map.put("preferredTeamId", preferredTeamId);
        map.put("allowDelegation", allowDelegation);
        map.put("contextSignals", new ArrayList<>(contextSignals));
        map.put("metadata", metadata);
        return map;
    }
}
