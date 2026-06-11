package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.approval.ToolRisk;
import com.nousresearch.hermes.collaboration.AgentRole;
import com.nousresearch.hermes.collaboration.Team;
import com.nousresearch.hermes.tenant.audit.AuditEvent;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Organization structure management APIs: teams/agents/roles inside tenant containers. */
public class OrgManagementHandler {
    private final TenantManager tenantManager;

    public OrgManagementHandler(TenantManager tenantManager) {
        this.tenantManager = tenantManager;
    }

    /** GET /api/org/manage/summary */
    public void summary(Context ctx) {
        var tenants = tenantManager.getAllTenants().values();
        long roles = tenants.stream().mapToLong(t -> t.listAgentRoles().size()).sum();
        long teams = tenants.stream().mapToLong(t -> t.getTeamManager().teamCount()).sum();
        ctx.json(Map.of(
            "tenants", tenants.size(),
            "agent_roles", roles,
            "teams", teams,
            "relationship", Map.of(
                "tenant", "Resource/container boundary",
                "org_management", "Maintains organization structure inside each tenant",
                "org_overview", "Read-only organization observability",
                "org_control", "Runtime governance and intervention"
            )
        ));
    }


    /** GET /api/org/manage/teams?tenantId=... */
    public void listTeams(Context ctx) {
        String tenantId = ctx.queryParam("tenantId");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext tenant : tenants(tenantId)) {
            for (Team team : tenant.getTeamManager().listTeams()) {
                rows.add(teamToMap(tenant, team));
            }
        }
        rows.sort((a, b) -> (String.valueOf(a.get("tenant_id")) + ":" + a.get("team_id")).compareTo(String.valueOf(b.get("tenant_id")) + ":" + b.get("team_id")));
        ctx.json(Map.of("teams", rows, "count", rows.size()));
    }

    /** POST /api/org/manage/teams */
    public void upsertTeam(Context ctx) {
        Map<String, Object> body = parseBody(ctx);
        TenantContext tenant = requireTenant(string(body, "tenant_id", string(body, "tenantId", "default")));
        String teamId = requireString(body, "team_id", "teamId");
        String name = requireString(body, "name", "name");
        String mission = string(body, "mission", "");
        String createdBy = string(body, "created_by", string(body, "createdBy", "dashboard"));
        List<String> members = list(body.get("members"));
        String lead = string(body, "lead", string(body, "lead_agent_id", string(body, "leadAgentId", "")));

        Team existing = tenant.getTeamManager().getTeam(teamId);
        Team team = existing != null ? existing : tenant.getTeamManager().createTeam(teamId, name, mission, createdBy);
        if (!members.isEmpty()) {
            for (String current : new ArrayList<>(team.getMemberIds())) {
                if (!members.contains(current)) team.removeMember(current);
            }
            members.forEach(team::addMember);
        }
        if (!lead.isBlank()) team.setLead(lead);

        tenant.getAuditLogger().log(existing != null ? AuditEvent.ORG_MANAGEMENT_TEAM_UPDATED : AuditEvent.ORG_MANAGEMENT_TEAM_CREATED, Map.of(
            "tenantId", tenant.getTenantId(),
            "scope", "org_management",
            "action", existing != null ? "update_team" : "create_team",
            "teamId", teamId,
            "name", name,
            "timestamp", System.currentTimeMillis()
        ));
        Map<String, Object> response = teamToMap(tenant, team);
        response.put("ok", true);
        ctx.json(response);
    }

    /** DELETE /api/org/manage/teams/{tenantId}/{teamId} */
    public void deleteTeam(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        String teamId = ctx.pathParam("teamId");
        boolean removed = tenant.getTeamManager().deleteTeam(teamId);
        if (!removed) {
            ctx.status(404).json(Map.of("error", "Team not found", "tenant_id", tenant.getTenantId(), "team_id", teamId));
            return;
        }
        tenant.getAuditLogger().log(AuditEvent.ORG_MANAGEMENT_TEAM_DELETED, Map.of(
            "tenantId", tenant.getTenantId(),
            "scope", "org_management",
            "action", "delete_team",
            "teamId", teamId,
            "timestamp", System.currentTimeMillis()
        ));
        ctx.json(Map.of("ok", true, "tenant_id", tenant.getTenantId(), "team_id", teamId));
    }

    /** GET /api/org/manage/roles?tenantId=... */
    public void listRoles(Context ctx) {
        String tenantId = ctx.queryParam("tenantId");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext tenant : tenants(tenantId)) {
            for (var entry : tenant.listAgentRoles().entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>(roleToMap(entry.getKey(), entry.getValue()));
                row.put("tenant_id", tenant.getTenantId());
                row.put("team_ids", teamsForAgent(tenant, entry.getKey()));
                rows.add(row);
            }
        }
        rows.sort((a, b) -> (String.valueOf(a.get("tenant_id")) + ":" + a.get("agent_id")).compareTo(String.valueOf(b.get("tenant_id")) + ":" + b.get("agent_id")));
        ctx.json(Map.of("roles", rows, "count", rows.size()));
    }

    /** POST /api/org/manage/roles */
    public void upsertRole(Context ctx) {
        Map<String, Object> body = parseBody(ctx);
        TenantContext tenant = requireTenant(string(body, "tenant_id", string(body, "tenantId", "default")));
        String agentId = requireString(body, "agent_id", "agentId");
        AgentRole previousRole = tenant.getAgentRole(agentId);
        AgentRole role = roleFromBody(body);
        tenant.registerAgentRole(agentId, role);
        if (body.containsKey("team_ids") || body.containsKey("teamIds")) {
            syncRoleTeams(tenant, agentId, list(body.getOrDefault("team_ids", body.get("teamIds"))));
        }
        tenant.getAuditLogger().log(previousRole == null ? AuditEvent.ORG_MANAGEMENT_ROLE_CREATED : AuditEvent.ORG_MANAGEMENT_ROLE_UPDATED, Map.of(
            "tenantId", tenant.getTenantId(),
            "scope", "org_management",
            "action", previousRole == null ? "create_agent_role" : "update_agent_role",
            "agentId", agentId,
            "role", role.getRoleName(),
            "timestamp", System.currentTimeMillis()
        ));
        Map<String, Object> response = new LinkedHashMap<>(roleToMap(agentId, role));
        response.put("tenant_id", tenant.getTenantId());
        response.put("team_ids", teamsForAgent(tenant, agentId));
        response.put("ok", true);
        ctx.json(response);
    }

    /** DELETE /api/org/manage/roles/{tenantId}/{agentId} */
    public void deleteRole(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        String agentId = ctx.pathParam("agentId");
        AgentRole removed = tenant.unregisterAgentRole(agentId);
        if (removed == null) {
            ctx.status(404).json(Map.of("error", "Agent role not found", "tenant_id", tenant.getTenantId(), "agent_id", agentId));
            return;
        }
        removeAgentFromAllTeams(tenant, agentId);
        tenant.getAuditLogger().log(AuditEvent.ORG_MANAGEMENT_ROLE_DELETED, Map.of(
            "tenantId", tenant.getTenantId(),
            "scope", "org_management",
            "action", "delete_agent_role",
            "agentId", agentId,
            "role", removed.getRoleName(),
            "timestamp", System.currentTimeMillis()
        ));
        ctx.json(Map.of("ok", true, "tenant_id", tenant.getTenantId(), "agent_id", agentId));
    }

    private List<TenantContext> tenants(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) return List.of(requireTenant(tenantId));
        return new ArrayList<>(tenantManager.getAllTenants().values());
    }

    private TenantContext requireTenant(String tenantId) {
        TenantContext tenant = tenantManager.getTenant(tenantId);
        if (tenant == null) throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        return tenant;
    }

    private static Map<String, Object> parseBody(Context ctx) {
        try {
            return ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static AgentRole roleFromBody(Map<String, Object> body) {
        String name = requireString(body, "role_name", "roleName");
        String description = string(body, "description", "");
        AgentRole.Level level = parseLevel(string(body, "level", "MID"));
        AgentRole role = new AgentRole(name, description, level);
        list(body.get("skills")).forEach(role::addSkill);
        list(body.get("responsibilities")).forEach(v -> role.responsibilities(v));
        String reportsTo = string(body, "reports_to", string(body, "reportsTo", ""));
        if (!reportsTo.isBlank()) role.reportsTo(reportsTo);
        list(body.get("collaborators")).forEach(v -> role.collaborators(v));
        list(body.get("manages")).forEach(v -> role.manages(v));
        list(body.get("allowed_tools")).forEach(v -> role.allowedTools(v));
        list(body.get("restricted_paths")).forEach(v -> role.restrictedPaths(v));
        String risk = string(body, "max_auto_risk", string(body, "maxAutoRisk", ""));
        if (!risk.isBlank()) {
            try { role.maxAutoRisk(ToolRisk.valueOf(risk.toUpperCase(Locale.ROOT))); } catch (Exception ignored) {}
        }
        if (body.get("min_task_score") instanceof Number n) role.minTaskScore(n.doubleValue());
        if (body.get("max_consecutive_failures") instanceof Number n) role.maxConsecutiveFailures(n.intValue());
        return role;
    }



    private static void syncRoleTeams(TenantContext tenant, String agentId, List<String> targetTeamIds) {
        if (targetTeamIds == null) return;
        for (Team team : tenant.getTeamManager().listTeams()) {
            boolean shouldContain = targetTeamIds.contains(team.getTeamId());
            boolean contains = team.getMemberIds().contains(agentId);
            if (shouldContain && !contains) team.addMember(agentId);
            if (!shouldContain && contains) team.removeMember(agentId);
        }
    }

    private static void removeAgentFromAllTeams(TenantContext tenant, String agentId) {
        for (Team team : tenant.getTeamManager().listTeams()) {
            if (team.getMemberIds().contains(agentId)) team.removeMember(agentId);
            if (agentId.equals(team.getLead())) team.setLead(null);
        }
    }

    private static List<String> teamsForAgent(TenantContext tenant, String agentId) {
        List<String> teamIds = new ArrayList<>();
        for (Team team : tenant.getTeamManager().listTeams()) {
            if (team.getMemberIds().contains(agentId)) teamIds.add(team.getTeamId());
        }
        return teamIds;
    }

    private static Map<String, Object> teamToMap(TenantContext tenant, Team team) {
        Map<String, Object> map = new LinkedHashMap<>(team.toMap());
        List<Map<String, Object>> memberRoles = new ArrayList<>();
        for (String memberId : team.getMemberIds()) {
            AgentRole role = tenant.getAgentRole(memberId);
            if (role != null) {
                Map<String, Object> roleMap = new LinkedHashMap<>(roleToMap(memberId, role));
                roleMap.put("is_lead", memberId.equals(team.getLead()));
                memberRoles.add(roleMap);
            } else {
                memberRoles.add(Map.of(
                    "agent_id", memberId,
                    "missing_role", true,
                    "is_lead", memberId.equals(team.getLead())
                ));
            }
        }
        map.put("member_roles", memberRoles);
        return map;
    }

    private static Map<String, Object> roleToMap(String agentId, AgentRole role) {
        Map<String, Object> map = new LinkedHashMap<>(role.toMap());
        map.put("agent_id", agentId);
        map.put("allowed_tools", new ArrayList<>(role.getAllowedTools()));
        map.put("restricted_paths", new ArrayList<>(role.getRestrictedPaths()));
        return map;
    }

    private static AgentRole.Level parseLevel(String raw) {
        try { return AgentRole.Level.valueOf(raw.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return AgentRole.Level.MID; }
    }

    private static List<String> list(Object value) {
        if (value instanceof List<?> l) return l.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        if (value == null) return List.of();
        return java.util.Arrays.stream(String.valueOf(value).split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static String requireString(Map<String, Object> body, String snake, String camel) {
        String value = string(body, snake, string(body, camel, ""));
        if (value.isBlank()) throw new IllegalArgumentException("Missing required field: " + snake);
        return value;
    }

    private static String string(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
