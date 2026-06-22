package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.tenant.core.TenantContext;

import java.util.*;

/**
 * Scores how suitable an agent is for a subtask.
 *
 * <p>This is the first step from heuristic token matching toward a real
 * organization scheduler. It combines:</p>
 * <ul>
 *   <li>Skill and role lexical match</li>
 *   <li>Role seniority</li>
 *   <li>Online availability on TenantBus</li>
 *   <li>Observed error rate from AgentObservability</li>
 *   <li>Failure history and success patterns from SelfEvolutionEngine</li>
 * </ul>
 */
public class CapabilityScorer {

    private CapabilityScorer() {}

    /** 计算 Agent 对子任务的匹配分数。 */
    public static CapabilityScore score(String subtask, String agentId, AgentRuntimeProfile role, TenantContext ctx) {
        return score(subtask, agentId, role, ctx, null);
    }

    /** 计算 Agent 对子任务的匹配分数。 */
    public static CapabilityScore score(String subtask, String agentId, AgentRuntimeProfile role, TenantContext ctx, String preferredTeamId) {
        String lower = subtask == null ? "" : subtask.toLowerCase();
        String[] tokens = lower.split("\\W+");

        List<String> matchedSkills = new ArrayList<>();
        Map<String, Double> components = new LinkedHashMap<>();

        double skillScore = skillMatchScore(tokens, role, matchedSkills);
        double roleScore = roleMatchScore(lower, tokens, role);
        double relevance = skillScore + roleScore;
        double levelScore = relevance > 0 ? levelBonus(role.getLevel()) : 0.0;
        double availabilityScore = availabilityBonus(agentId, ctx);
        double reliabilityScore = reliabilityBonus(agentId, ctx);
        double evolutionScore = evolutionBonus(agentId, lower, role, ctx);
        double teamPreferenceScore = teamPreferenceBonus(agentId, ctx, preferredTeamId);
        double manualOverrideScore = manualOverrideScore(role);

        components.put("skill_match", skillScore);
        components.put("role_match", roleScore);
        components.put("level", levelScore);
        components.put("availability", availabilityScore);
        components.put("reliability", reliabilityScore);
        components.put("evolution", evolutionScore);
        components.put("team_preference", teamPreferenceScore);
        components.put("manual_override", manualOverrideScore);

        double total = skillScore + roleScore + levelScore + availabilityScore + reliabilityScore + evolutionScore + teamPreferenceScore + manualOverrideScore;
        return new CapabilityScore(agentId, role.getRoleName(), total, matchedSkills, components);
    }

    private static double skillMatchScore(String[] tokens, AgentRuntimeProfile role, List<String> matchedSkills) {
        double score = 0.0;
        for (String skill : role.getSkills()) {
            String s = skill.toLowerCase();
            for (String token : tokens) {
                if (token.length() < 3) continue;
                if (s.contains(token) || token.contains(s)) {
                    score += 1.0;
                    if (!matchedSkills.contains(skill)) matchedSkills.add(skill);
                    break;
                }
            }
        }
        return score;
    }

    private static double roleMatchScore(String lower, String[] tokens, AgentRuntimeProfile role) {
        String roleLower = role.getRoleName().toLowerCase();
        if (lower.contains(roleLower)) return 2.0;

        double score = 0.0;
        for (String roleToken : roleLower.split("\\W+")) {
            if (roleToken.length() < 3) continue;
            for (String taskToken : tokens) {
                if (taskToken.length() < 3) continue;
                if (roleToken.contains(taskToken) || taskToken.contains(roleToken)) {
                    score += 1.0;
                    break;
                }
            }
        }
        return score;
    }

    private static double levelBonus(AgentRuntimeProfile.Level level) {
        return switch (level) {
            case LEAD -> 0.5;
            case SENIOR -> 0.3;
            case MID -> 0.1;
            case JUNIOR -> 0.0;
        };
    }

    private static double availabilityBonus(String agentId, TenantContext ctx) {
        if (ctx == null) return 0.0;
        try {
            return ctx.getTenantBus().isRegistered(agentId) ? 0.35 : -0.15;
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static double reliabilityBonus(String agentId, TenantContext ctx) {
        if (ctx == null) return 0.0;
        try {
            var status = ctx.getObservability().getStatus(agentId);
            if (status == null || status.getTotalTasks() == 0) return 0.0;
            double errorPenalty = Math.min(1.0, status.getErrorRate());
            double latencyPenalty = Math.min(0.35, status.getAverageLatencyMs() / 120_000.0);
            return 0.3 - errorPenalty - latencyPenalty;
        } catch (Exception ignored) {
            return 0.0;
        }
    }


    private static double teamPreferenceBonus(String agentId, TenantContext ctx, String preferredTeamId) {
        if (ctx == null || preferredTeamId == null || preferredTeamId.isBlank()) return 0.0;
        try {
            TeamRuntime team = ctx.getTeamManager().getTeam(preferredTeamId);
            if (team == null) return 0.0;
            if (!team.hasMember(agentId)) return 0.0;
            return agentId != null && agentId.equals(team.getLead()) ? 1.4 : 1.25;
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    public static boolean clearExpiredManualOverride(AgentRuntimeProfile role) {
        try {
            Object expiresAt = role.getMetrics().get("manual_expires_at");
            if (expiresAt == null) return false;
            long deadline = expiresAt instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(expiresAt));
            if (deadline <= 0 || System.currentTimeMillis() < deadline) return false;
            role.removeMetric("manual_disabled");
            role.removeMetric("manual_penalty");
            role.removeMetric("manual_expires_at");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static double manualOverrideScore(AgentRuntimeProfile role) {
        try {
            clearExpiredManualOverride(role);
            Object disabled = role.getMetrics().get("manual_disabled");
            if (Boolean.TRUE.equals(disabled) || "true".equalsIgnoreCase(String.valueOf(disabled))) {
                return -10.0;
            }
            Object penalty = role.getMetrics().get("manual_penalty");
            if (penalty == null) return 0.0;
            double value = penalty instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(penalty));
            return -Math.max(0.0, Math.min(5.0, value));
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static double evolutionBonus(String agentId, String lower, AgentRuntimeProfile role, TenantContext ctx) {
        if (ctx == null) return 0.0;
        try {
            var engine = ctx.getEvolutionEngine();
            double score = 0.0;

            // Penalize recent unresolved/total failures for this agent.
            int recentFailures = engine.getAgentFailures(agentId, 20).size();
            score -= Math.min(0.5, recentFailures * 0.08);

            // Reward success patterns that look relevant to the task or role.
            for (String pattern : engine.getSuccessPatterns(agentId)) {
                String p = pattern.toLowerCase();
                if (lower.contains(p) || p.contains(role.getRoleName().toLowerCase())) {
                    score += 0.2;
                }
            }
            return score;
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    public record CapabilityScore(
        String agentId,
        String roleName,
        double total,
        List<String> matchedSkills,
        Map<String, Double> components
    ) {}
}
