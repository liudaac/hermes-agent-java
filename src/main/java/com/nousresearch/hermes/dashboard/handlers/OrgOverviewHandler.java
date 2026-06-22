package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.collaboration.AgentRuntimeProfile;
import com.nousresearch.hermes.collaboration.GovernancePolicy;
import com.nousresearch.hermes.collaboration.OrgHealthChecker;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Organization-level overview endpoint for the AI-native org dashboard.
 * Aggregates all tenant metrics into a single view using real collaboration data.
 */
public class OrgOverviewHandler {
    private static final Logger logger = LoggerFactory.getLogger(OrgOverviewHandler.class);
    
    private final Supplier<Map<String, Object>> statsSupplier;
    private volatile TenantManager tenantManager;
    
    public OrgOverviewHandler() {
        this(null);
    }
    
    public OrgOverviewHandler(Supplier<Map<String, Object>> statsSupplier) {
        this.statsSupplier = statsSupplier;
    }
    
    /** Set tenant manager for dynamic aggregation */
    public void setTenantManager(TenantManager tenantManager) {
        this.tenantManager = tenantManager;
    }
    
    /**
     * GET /api/organization/overview
     * Returns aggregated metrics for all tenants/agents in the organization.
     */
    public void getOverview(Context ctx) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timestamp", Instant.now().toString());
            result.put("version", "1.0");
            
            // Aggregate from stats supplier if available
            if (statsSupplier != null) {
                result.putAll(statsSupplier.get());
            }
            
            // Aggregate from tenant contexts
            if (tenantManager != null) {
                result.putAll(buildTenantAggregate());
            }
            
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Failed to build org overview: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }
    
    /**
     * GET /api/organization/agents
     * List all AI agents with their roles and status across all tenants.
     */
    public void getAgents(Context ctx) {
        try {
            List<Map<String, Object>> agents = new ArrayList<>();
            
            if (tenantManager != null) {
                for (TenantContext tc : tenantManager.getAllTenants().values()) {
                    for (Map.Entry<String, AgentRuntimeProfile> entry : tc.listAgentRoles().entrySet()) {
                        agents.add(buildAgentInfo(tc, entry.getKey(), entry.getValue()));
                    }
                }
            }
            
            ctx.json(Map.of("agents", agents, "total", agents.size()));
        } catch (Exception e) {
            logger.error("Failed to list agents: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }
    
    /**
     * GET /api/organization/health
     * Aggregated health check from OrgHealthChecker across tenants.
     */
    public void getHealth(Context ctx) {
        try {
            Map<String, Object> health = new LinkedHashMap<>();
            
            if (tenantManager != null) {
                int totalAgents = 0;
                int warnings = 0;
                int critical = 0;
                double sumScore = 0;
                long totalTokens = 0;
                
                for (TenantContext tc : tenantManager.getAllTenants().values()) {
                    OrgHealthChecker checker = tc.getOrgHealthChecker();
                    Map<String, Object> overview = checker.getOrgOverview();
                    totalAgents += ((Number) overview.getOrDefault("total_agents", 0)).intValue();
                    warnings += ((Number) overview.getOrDefault("warnings", 0)).intValue();
                    critical += ((Number) overview.getOrDefault("critical", 0)).intValue();
                    sumScore += ((Number) overview.getOrDefault("avg_task_score", 0.0)).doubleValue()
                        * ((Number) overview.getOrDefault("active_agents", 0)).intValue();
                    totalTokens += ((Number) overview.getOrDefault("total_tokens", 0L)).longValue();
                }
                
                health.put("total_agents", totalAgents);
                health.put("warnings", warnings);
                health.put("critical", critical);
                health.put("avg_task_score", totalAgents > 0 ? sumScore / totalAgents : 0);
                health.put("total_tokens", totalTokens);
            } else {
                health.put("total_agents", 0);
                health.put("warnings", 0);
                health.put("critical", 0);
                health.put("avg_task_score", 0.0);
                health.put("total_tokens", 0L);
            }
            
            health.put("timestamp", Instant.now().toString());
            ctx.json(health);
        } catch (Exception e) {
            logger.error("Failed to build health: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }
    
    // ---- Private helpers ----
    
    private Map<String, Object> buildTenantAggregate() {
        Map<String, Object> agg = new LinkedHashMap<>();
        List<Map<String, Object>> tenants = new ArrayList<>();
        int totalRoles = 0;
        int pausedAgents = 0;
        long totalTokensUsed = 0;
        
        for (TenantContext tc : tenantManager.getAllTenants().values()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("tenant_id", tc.getTenantId());
            t.put("agents", tc.getActiveAgentCount());
            t.put("roles", tc.listAgentRoles().size());
            t.put("sessions", tc.getActiveSessionCount());
            
            GovernancePolicy policy = tc.getGovernancePolicy();
            t.put("tokens_used_today", policy.getTokensUsed());
            t.put("tokens_remaining", policy.getTokensRemaining());
            t.put("agent_paused", policy.isPaused());
            t.put("collaboration_initialized", tc.isCollaborationInitialized());
            
            totalRoles += tc.listAgentRoles().size();
            if (policy.isPaused()) pausedAgents++;
            totalTokensUsed += policy.getTokensUsed();
            
            tenants.add(t);
        }
        
        agg.put("tenants", tenants);
        agg.put("total_tenants", tenants.size());
        agg.put("total_roles", totalRoles);
        agg.put("paused_agents", pausedAgents);
        agg.put("total_tokens_used", totalTokensUsed);
        return agg;
    }
    
    private Map<String, Object> buildAgentInfo(TenantContext tc, String agentId, AgentRuntimeProfile role) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("agent_id", agentId);
        info.put("tenant_id", tc.getTenantId());
        info.put("role_name", role.getRoleName());
        info.put("description", role.getDescription());
        info.put("level", role.getLevel().name());
        info.put("skills", new ArrayList<>(role.getSkills()));
        info.put("responsibilities", new ArrayList<>(role.getResponsibilities()));
        info.put("reports_to", role.getReportsTo());
        info.put("collaborators", new ArrayList<>(role.getCollaborators()));
        info.put("manages", new ArrayList<>(role.getManages()));
        info.put("allowed_tools", new ArrayList<>(role.getAllowedTools()));
        info.put("metrics", new LinkedHashMap<>(role.getMetrics()));
        return info;
    }
}
