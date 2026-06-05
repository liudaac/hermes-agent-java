package com.nousresearch.hermes.dashboard.handlers;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Organization-level overview endpoint for the AI-native org dashboard.
 * Aggregates all tenant metrics into a single view.
 */
public class OrgOverviewHandler {
    private static final Logger logger = LoggerFactory.getLogger(OrgOverviewHandler.class);
    
    private final Supplier<Map<String, Object>> statsSupplier;
    
    public OrgOverviewHandler() {
        this(null);
    }
    
    public OrgOverviewHandler(Supplier<Map<String, Object>> statsSupplier) {
        this.statsSupplier = statsSupplier;
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
            
            ctx.json(result);
        } catch (Exception e) {
            logger.error("Failed to build org overview: {}", e.getMessage());
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }
    
    /**
     * GET /api/organization/agents
     */
    public void getAgents(Context ctx) {
        try {
            List<Map<String, Object>> agents = new ArrayList<>();
            ctx.json(Map.of("agents", agents, "total", agents.size()));
        } catch (Exception e) {
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }
    
    /**
     * GET /api/organization/health
     */
    public void getHealth(Context ctx) {
        try {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("overall_score", 0.5);
            health.put("weak_topics", List.of());
            health.put("recommendations", List.of());
            ctx.json(health);
        } catch (Exception e) {
            ctx.status(500).result("Error: " + e.getMessage());
        }
    }
}