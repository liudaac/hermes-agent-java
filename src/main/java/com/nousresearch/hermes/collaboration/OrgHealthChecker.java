package com.nousresearch.hermes.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public class OrgHealthChecker {
    private static final Logger logger = LoggerFactory.getLogger(OrgHealthChecker.class);
    private final TenantBus bus;
    private final Map<String, AgentHealth> agentHealth = new LinkedHashMap<>();
    
    public OrgHealthChecker(TenantBus bus) { this.bus = bus; }
    
    public void updateHealth(String agentId, double taskScore, int failures, long tokensUsed, long tokenBudget) {
        AgentHealth h = agentHealth.computeIfAbsent(agentId, k -> new AgentHealth(agentId));
        h.lastTaskScore = taskScore;
        h.consecutiveFailures = failures;
        h.tokensUsed = tokensUsed;
        h.tokenBudget = tokenBudget;
        h.lastCheckin = Instant.now();
    }
    
    public Map<String, Object> getOrgOverview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("total_agents", agentHealth.size());
        
        double sumScore = 0;
        int active = 0, warning = 0, critical = 0;
        long totalTokens = 0;
        for (AgentHealth h : agentHealth.values()) {
            if (h.lastTaskScore >= 0) { sumScore += h.lastTaskScore; active++; }
            if (h.consecutiveFailures >= 2) warning++;
            if (h.consecutiveFailures >= 5) critical++;
            totalTokens += h.tokensUsed;
        }
        
        result.put("active_agents", active);
        result.put("warnings", warning);
        result.put("critical", critical);
        result.put("avg_task_score", active > 0 ? sumScore / active : 0);
        result.put("total_tokens", totalTokens);
        result.put("bus_queue_depth", bus.getQueueDepth());
        result.put("bus_pending_replies", bus.getPendingReplyCount());
        result.put("active_negotiations", bus instanceof TenantBus ? 0 : 0);
        return result;
    }
    
    static class AgentHealth {
        final String agentId;
        double lastTaskScore = -1;
        int consecutiveFailures;
        long tokensUsed;
        long tokenBudget;
        Instant lastCheckin;
        AgentHealth(String id) { this.agentId = id; }
    }
}