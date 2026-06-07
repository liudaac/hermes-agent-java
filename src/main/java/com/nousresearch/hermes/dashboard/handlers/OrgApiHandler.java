package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.org.auth.PermissionPolicy;
import com.nousresearch.hermes.org.auth.RoleBasedAccessControl;
import com.nousresearch.hermes.org.compliance.ComplianceFramework;
import com.nousresearch.hermes.org.distributed.AgentRegistry;
import com.nousresearch.hermes.org.distributed.AgentRouter;
import com.nousresearch.hermes.org.evolution.SelfEvolutionEngine;
import com.nousresearch.hermes.org.handoff.HandoffProtocol;
import com.nousresearch.hermes.org.identity.AgentIdentityManager;
import com.nousresearch.hermes.org.knowledge.OrganizationalKnowledgeBase;
import com.nousresearch.hermes.org.market.AgentMarketplace;
import com.nousresearch.hermes.org.market.CostAttribution;
import com.nousresearch.hermes.org.observe.AgentObservability;
import com.nousresearch.hermes.org.workflow.WorkflowEngine;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * REST API endpoints for all 12 org modules.
 * Registered by DashboardServer on startup.
 */
public class OrgApiHandler {
    private static final Logger log = LoggerFactory.getLogger(OrgApiHandler.class);

    private final Map<String, Object> modules = new LinkedHashMap<>();

    public OrgApiHandler() {}

    /** Wire a module instance. */
    public OrgApiHandler with(String name, Object instance) {
        modules.put(name, instance);
        return this;
    }

    @SuppressWarnings("unchecked")
    private <T> T get(String name) { return (T) modules.get(name); }

    // --- identity ---
    public void identitySummary(Context ctx) {
        var mgr = get("identity");
        ctx.json(mgr != null ? ((AgentIdentityManager)mgr).getSummary() : Map.of("status","not_wired"));
    }
    public void identityList(Context ctx) {
        var mgr = (AgentIdentityManager)get("identity");
        ctx.json(mgr != null ? mgr.listActive().stream().map(a -> a.toMap()).toList() : List.of());
    }
    public void identityWarnings(Context ctx) {
        var mgr = (AgentIdentityManager)get("identity");
        ctx.json(mgr != null ? mgr.getOrganizationalWarnings() : Map.of());
    }

    // --- handoff ---
    public void handoffSummary(Context ctx) {
        var hp = (HandoffProtocol)get("handoff");
        ctx.json(hp != null ? hp.getSummary() : Map.of("status","not_wired"));
    }
    public void handoffPending(Context ctx) {
        var hp = (HandoffProtocol)get("handoff");
        var list = hp != null ? hp.getAllPending().stream().map(h -> Map.of(
            "id", h.getHandoffId(), "agent", h.getSourceAgentId(),
            "summary", h.getSummary(), "priority", h.getPriority().name(),
            "status", h.getStatus().name(), "created", h.getCreatedAt().toString()
        )).toList() : List.of();
        ctx.json(Map.of("handoffs", list));
    }

    // --- auth ---
    public void authSummary(Context ctx) {
        var pp = (PermissionPolicy)get("auth");
        ctx.json(pp != null ? pp.getSummary() : Map.of("status","not_wired"));
    }
    public void authSubjects(Context ctx) {
        var pp = (PermissionPolicy)get("auth");
        ctx.json(pp != null ? pp.rbac().getAllAssignments() : Map.of());
    }

    // --- knowledge ---
    public void knowledgeSummary(Context ctx) {
        var kb = (OrganizationalKnowledgeBase)get("knowledge");
        ctx.json(kb != null ? kb.getSummary() : Map.of("status","not_wired"));
    }
    public void knowledgeSearch(Context ctx) {
        var kb = (OrganizationalKnowledgeBase)get("knowledge");
        String q = ctx.queryParam("q");
        int n = Integer.parseInt(ctx.queryParam("n") != null ? ctx.queryParam("n") : "10");
        java.util.List<com.nousresearch.hermes.org.knowledge.KnowledgeEntry> results = q != null ? kb.search(q, n) : java.util.Collections.emptyList();
        ctx.json(Map.of("results", results.stream().map(r -> r.toMap()).toList()));
    }

    // --- workflow ---
    public void workflowSummary(Context ctx) {
        var we = (WorkflowEngine)get("workflow");
        ctx.json(we != null ? we.getSummary() : Map.of("status","not_wired"));
    }
    public void workflowList(Context ctx) {
        var we = (WorkflowEngine)get("workflow");
        var list = we != null ? we.listActive().stream().map(w -> w.toMap()).toList() : List.of();
        ctx.json(Map.of("workflows", list));
    }
    public void workflowWaiting(Context ctx) {
        var we = (WorkflowEngine)get("workflow");
        String reviewer = ctx.queryParam("reviewer");
        var list = we != null ? we.listWaitingForHuman(reviewer).stream().map(w -> w.toMap()).toList() : List.of();
        ctx.json(Map.of("waiting", list));
    }

    // --- market (templates + cost) ---
    public void marketSummary(Context ctx) {
        var mp = (AgentMarketplace)get("market");
        ctx.json(mp != null ? mp.getSummary() : Map.of("status","not_wired"));
    }
    public void marketTemplates(Context ctx) {
        var mp = (AgentMarketplace)get("market");
        var list = mp != null ? mp.listAll().stream().map(t -> t.toMap()).toList() : List.of();
        ctx.json(Map.of("templates", list));
    }
    public void costSummary(Context ctx) {
        var ca = get("cost");
        ctx.json(ca != null ? ((CostAttribution)ca).getSummary() : Map.of("status","not_wired"));
    }

    // --- observe ---
    public void observeSummary(Context ctx) {
        var ao = (AgentObservability)get("observe");
        ctx.json(ao != null ? ao.getSummary() : Map.of("status","not_wired"));
    }
    public void observeAnomalies(Context ctx) {
        var ao = (AgentObservability)get("observe");
        int n = Integer.parseInt(ctx.queryParam("n") != null ? ctx.queryParam("n") : "20");
        var list = ao != null ? ao.getRecentAnomalies(n).stream().map(a -> Map.of(
            "type", a.type().name(), "agent", a.agentId(),
            "message", a.message(), "time", a.time().toString()
        )).toList() : List.of();
        ctx.json(Map.of("anomalies", list));
    }

    // --- distributed ---
    public void distributedSummary(Context ctx) {
        var reg = (AgentRegistry)get("distributed");
        ctx.json(reg != null ? reg.getLoadSummary() : Map.of("status","not_wired"));
    }
    public void distributedNodes(Context ctx) {
        var reg = (AgentRegistry)get("distributed");
        var list = reg != null ? reg.listAll().stream().map(n -> n.toMap()).toList() : List.of();
        ctx.json(Map.of("nodes", list));
    }

    // --- evolution ---
    public void evolutionSummary(Context ctx) {
        var se = (SelfEvolutionEngine)get("evolution");
        ctx.json(se != null ? se.getSummary() : Map.of("status","not_wired"));
    }
    public void evolutionFailures(Context ctx) {
        var se = (SelfEvolutionEngine)get("evolution");
        String agentId = ctx.queryParam("agent");
        var list = se != null && agentId != null
            ? se.getAgentFailures(agentId, 20).stream().map(f -> f.toMap()).toList()
            : se != null ? se.getAgentFailures("*", 20).stream().map(f -> f.toMap()).toList() : List.of();
        ctx.json(Map.of("failures", list));
    }
    public void evolutionPatterns(Context ctx) {
        var se = (SelfEvolutionEngine)get("evolution");
        ctx.json(se != null ? Map.of(
            "root_causes", se.getRootCauseDistribution(),
            "patterns", se.detectOrgPatterns(2).stream().map(p -> Map.of(
                "cause", p.rootCause().name(), "count", p.occurrences(), "detail", p.detail()
            )).toList()
        ) : Map.of());
    }

    // --- compliance ---
    public void complianceSummary(Context ctx) {
        var cf = (ComplianceFramework)get("compliance");
        ctx.json(cf != null ? cf.getSummary() : Map.of("status","not_wired"));
    }

    /** Aggregated org-level summary joining all modules. */
    public void fullOrgSummary(Context ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : modules.entrySet()) {
            try {
                Object inst = entry.getValue();
                if (inst instanceof AgentIdentityManager m) result.put(entry.getKey(), m.getSummary());
                else if (inst instanceof HandoffProtocol h) result.put(entry.getKey(), h.getSummary());
                else if (inst instanceof PermissionPolicy p) result.put(entry.getKey(), p.getSummary());
                else if (inst instanceof OrganizationalKnowledgeBase k) result.put(entry.getKey(), k.getSummary());
                else if (inst instanceof WorkflowEngine w) result.put(entry.getKey(), w.getSummary());
                else if (inst instanceof AgentMarketplace m) result.put(entry.getKey(), m.getSummary());
                else if (inst instanceof CostAttribution c) result.put(entry.getKey(), c.getSummary());
                else if (inst instanceof AgentObservability o) result.put(entry.getKey(), o.getSummary());
                else if (inst instanceof AgentRegistry r) result.put(entry.getKey(), r.getLoadSummary());
                else if (inst instanceof SelfEvolutionEngine e) result.put(entry.getKey(), e.getSummary());
                else if (inst instanceof ComplianceFramework f) result.put(entry.getKey(), f.getSummary());
            } catch (Exception e) {
                result.put(entry.getKey(), Map.of("error", e.getMessage()));
            }
        }
        ctx.json(result);
    }
}