package com.nousresearch.hermes.policy;

import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.util.*;

/**
 * Workspace and agent-level skill/tool policy service.
 *
 * <p>Policy resolution order (most specific wins):</p>
 * <ol>
 *   <li>Agent blueprint allowed/denied lists</li>
 *   <li>Workspace policy allowed/denied lists</li>
 *   <li>Tenant security policy (fallback, not managed here)</li>
 * </ol>
 */
public class PolicyService {
    private final FileWorkspacePolicyRepository repository;
    private final WorkspaceService workspaceService;
    private final TeamBlueprintService teamBlueprintService;

    public PolicyService(WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileWorkspacePolicyRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService, teamBlueprintService);
    }

    public PolicyService(Path workspacesRoot, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileWorkspacePolicyRepository(workspacesRoot), workspaceService, teamBlueprintService);
    }

    public PolicyService(FileWorkspacePolicyRepository repository, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.teamBlueprintService = teamBlueprintService;
    }

    /** Get or create workspace policy. */
    public WorkspacePolicyRecord getOrCreatePolicy(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.findByWorkspaceId(workspaceId).orElseGet(() -> {
            WorkspacePolicyRecord record = new WorkspacePolicyRecord().setWorkspaceId(workspaceId);
            repository.save(record);
            return record;
        });
    }

    /** Update workspace policy. */
    public WorkspacePolicyRecord updatePolicy(String workspaceId,
                                               List<String> allowedSkills, List<String> deniedSkills,
                                               List<String> allowedTools, List<String> deniedTools) {
        workspaceService.requireWorkspace(workspaceId);
        WorkspacePolicyRecord record = getOrCreatePolicy(workspaceId);
        if (allowedSkills != null) record.setAllowedSkills(new ArrayList<>(allowedSkills));
        if (deniedSkills != null) record.setDeniedSkills(new ArrayList<>(deniedSkills));
        if (allowedTools != null) record.setAllowedTools(new ArrayList<>(allowedTools));
        if (deniedTools != null) record.setDeniedTools(new ArrayList<>(deniedTools));
        repository.save(record);
        return record;
    }

    /** Resolve effective allowed skills for an agent. */
    public Set<String> resolveAllowedSkills(String workspaceId, String teamId, String agentId) {
        WorkspacePolicyRecord workspacePolicy = getOrCreatePolicy(workspaceId);
        AgentBlueprintRecord agent = findAgent(workspaceId, teamId, agentId);

        Set<String> allowed = new HashSet<>();
        // Workspace whitelist
        if (!workspacePolicy.getAllowedSkills().isEmpty()) {
            allowed.addAll(workspacePolicy.getAllowedSkills());
        }
        // Agent whitelist narrows workspace
        if (agent != null && !agent.getAllowedSkills().isEmpty()) {
            if (allowed.isEmpty()) {
                allowed.addAll(agent.getAllowedSkills());
            } else {
                allowed.retainAll(agent.getAllowedSkills());
            }
        }
        // Denied skills removed
        Set<String> denied = new HashSet<>();
        denied.addAll(workspacePolicy.getDeniedSkills());
        if (agent != null) {
            // Agent has no deniedSkills field; workspace denied is the floor
        }
        allowed.removeAll(denied);
        return allowed;
    }

    /** Resolve effective allowed tools for an agent. */
    public Set<String> resolveAllowedTools(String workspaceId, String teamId, String agentId) {
        WorkspacePolicyRecord workspacePolicy = getOrCreatePolicy(workspaceId);
        AgentBlueprintRecord agent = findAgent(workspaceId, teamId, agentId);

        Set<String> allowed = new HashSet<>();
        // Workspace whitelist
        if (!workspacePolicy.getAllowedTools().isEmpty()) {
            allowed.addAll(workspacePolicy.getAllowedTools());
        }
        // Agent whitelist narrows workspace
        if (agent != null && !agent.getAllowedTools().isEmpty()) {
            if (allowed.isEmpty()) {
                allowed.addAll(agent.getAllowedTools());
            } else {
                allowed.retainAll(agent.getAllowedTools());
            }
        }
        // Denied tools removed
        Set<String> denied = new HashSet<>();
        denied.addAll(workspacePolicy.getDeniedTools());
        allowed.removeAll(denied);
        return allowed;
    }

    /** Check if a skill is permitted for an agent. */
    public boolean isSkillPermitted(String workspaceId, String teamId, String agentId, String skillName) {
        Set<String> allowed = resolveAllowedSkills(workspaceId, teamId, agentId);
        // If no whitelist defined, permit all except denied
        WorkspacePolicyRecord wp = getOrCreatePolicy(workspaceId);
        if (allowed.isEmpty() && wp.getAllowedSkills().isEmpty()) {
            return !wp.getDeniedSkills().contains(skillName);
        }
        return allowed.contains(skillName);
    }

    /** Resolve effective denied tools for an agent (workspace denied list; no agent-level denied yet). */
    public Set<String> resolveDeniedTools(String workspaceId, String teamId, String agentId) {
        WorkspacePolicyRecord workspacePolicy = getOrCreatePolicy(workspaceId);
        Set<String> denied = new HashSet<>();
        denied.addAll(workspacePolicy.getDeniedTools());
        return denied;
    }

    /** Resolve effective denied skills for an agent. */
    public Set<String> resolveDeniedSkills(String workspaceId, String teamId, String agentId) {
        WorkspacePolicyRecord workspacePolicy = getOrCreatePolicy(workspaceId);
        Set<String> denied = new HashSet<>();
        denied.addAll(workspacePolicy.getDeniedSkills());
        return denied;
    }

    /** Check if a tool is permitted for an agent. */
    public boolean isToolPermitted(String workspaceId, String teamId, String agentId, String toolName) {
        Set<String> allowed = resolveAllowedTools(workspaceId, teamId, agentId);
        WorkspacePolicyRecord wp = getOrCreatePolicy(workspaceId);
        if (allowed.isEmpty() && wp.getAllowedTools().isEmpty()) {
            return !wp.getDeniedTools().contains(toolName);
        }
        return allowed.contains(toolName);
    }

    /** Check if a specific tool call requires approval for the given agent. */
    public ApprovalCheckResult checkToolApprovalRequired(String workspaceId, String teamId, String agentId,
                                                           String toolName, Map<String, Object> toolArgs) {
        AgentBlueprintRecord agent = findAgent(workspaceId, teamId, agentId);
        if (agent == null || agent.getToolApprovalRules() == null || agent.getToolApprovalRules().isEmpty()) {
            return ApprovalCheckResult.noApprovalNeeded();
        }

        String argsStr = toolArgs != null ? toolArgs.toString().toLowerCase() : "";

        for (String rule : agent.getToolApprovalRules()) {
            if (rule == null || rule.isBlank()) continue;
            String normalized = rule.trim().toLowerCase();

            // Rule: always — every tool call needs approval
            if ("always".equals(normalized)) {
                return ApprovalCheckResult.approvalNeeded(agentId, rule, "Every tool call requires approval");
            }

            // Rule: high-risk — high-risk tools need approval
            if ("high-risk".equals(normalized) || "high-risk-tools".equals(normalized)) {
                if (isHighRiskTool(toolName)) {
                    return ApprovalCheckResult.approvalNeeded(agentId, rule,
                        "High-risk tool: " + toolName);
                }
            }

            // Rule: external — external-facing tools need approval
            if ("external".equals(normalized) || "external-tools".equals(normalized)) {
                if (isExternalTool(toolName)) {
                    return ApprovalCheckResult.approvalNeeded(agentId, rule,
                        "External tool: " + toolName);
                }
            }

            // Rule: tool:<name> — specific tool needs approval
            if (normalized.startsWith("tool:")) {
                String targetTool = normalized.substring("tool:".length()).trim();
                if (toolName.toLowerCase().equals(targetTool)) {
                    return ApprovalCheckResult.approvalNeeded(agentId, rule,
                        "Tool requires approval: " + toolName);
                }
            }

            // Rule: contains:<keyword> — tool args contain keyword
            if (normalized.startsWith("contains:")) {
                String keyword = normalized.substring("contains:".length()).trim();
                if (argsStr.contains(keyword)) {
                    return ApprovalCheckResult.approvalNeeded(agentId, rule,
                        "Keyword '" + keyword + "' detected in tool arguments");
                }
            }
        }
        return ApprovalCheckResult.noApprovalNeeded();
    }

    private static boolean isHighRiskTool(String toolName) {
        String lower = toolName.toLowerCase();
        return lower.contains("exec") || lower.contains("delete") || lower.contains("remove")
            || lower.contains("write") || lower.contains("send_") || lower.contains("post")
            || lower.contains("email") || lower.contains("payment") || lower.contains("refund")
            || lower.contains("transfer") || lower.contains("publish");
    }

    private static boolean isExternalTool(String toolName) {
        String lower = toolName.toLowerCase();
        return lower.contains("send") || lower.contains("email") || lower.contains("post")
            || lower.contains("tweet") || lower.contains("message") || lower.contains("browser")
            || lower.contains("web_fetch") || lower.contains("http");
    }

    /** Check if any agent in the team requires approval for this action. */
    public ApprovalCheckResult checkApprovalRequired(String workspaceId, String teamId, String actionType, String userInput) {
        AgentBlueprintRecord agent = findAnyAgentWithApprovalRules(workspaceId, teamId);
        if (agent == null) {
            return ApprovalCheckResult.noApprovalNeeded();
        }
        for (String rule : agent.getApprovalRules()) {
            if (rule == null || rule.isBlank()) continue;
            String normalized = rule.trim().toLowerCase();
            // Rule: always — always needs approval
            if ("always".equals(normalized)) {
                return ApprovalCheckResult.approvalNeeded(agent.getAgentId(), rule, "Always requires approval");
            }
            // Rule: high-risk — check if user input contains high-risk keywords
            if ("high-risk".equals(normalized)) {
                if (containsHighRisk(userInput)) {
                    return ApprovalCheckResult.approvalNeeded(agent.getAgentId(), rule, "High-risk content detected");
                }
            }
            // Rule: external-action — any external-facing action
            if ("external-action".equals(normalized)) {
                if (containsExternalAction(userInput)) {
                    return ApprovalCheckResult.approvalNeeded(agent.getAgentId(), rule, "External action detected");
                }
            }
            // Rule: contains keyword
            if (normalized.startsWith("contains:")) {
                String keyword = normalized.substring("contains:".length()).trim();
                if (userInput != null && userInput.toLowerCase().contains(keyword)) {
                    return ApprovalCheckResult.approvalNeeded(agent.getAgentId(), rule, "Keyword match: " + keyword);
                }
            }
        }
        return ApprovalCheckResult.noApprovalNeeded();
    }

    private AgentBlueprintRecord findAgent(String workspaceId, String teamId, String agentId) {
        if (teamId == null || teamId.isBlank() || agentId == null || agentId.isBlank()) {
            return null;
        }
        try {
            var team = teamBlueprintService.requireTeamBlueprint(workspaceId, teamId);
            var version = team.getVersions().stream()
                .filter(v -> v.getVersion() == team.getActiveVersion())
                .findFirst()
                .orElse(null);
            if (version == null || version.getAgents() == null) return null;
            return version.getAgents().stream()
                .filter(a -> agentId.equals(a.getAgentId()))
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private AgentBlueprintRecord findAnyAgentWithApprovalRules(String workspaceId, String teamId) {
        if (teamId == null || teamId.isBlank()) return null;
        try {
            var team = teamBlueprintService.requireTeamBlueprint(workspaceId, teamId);
            var version = team.getVersions().stream()
                .filter(v -> v.getVersion() == team.getActiveVersion())
                .findFirst()
                .orElse(null);
            if (version == null || version.getAgents() == null) return null;
            return version.getAgents().stream()
                .filter(a -> a.getApprovalRules() != null && !a.getApprovalRules().isEmpty())
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean containsHighRisk(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase();
        return lower.contains("refund") || lower.contains("payment") || lower.contains("退款") || lower.contains("支付")
            || lower.contains("delete") || lower.contains("remove") || lower.contains("删除");
    }

    private static boolean containsExternalAction(String input) {
        if (input == null) return false;
        String lower = input.toLowerCase();
        return lower.contains("send") || lower.contains("email") || lower.contains("post") || lower.contains("tweet")
            || lower.contains("message") || lower.contains("browser") || lower.contains("登录");
    }

    public record ApprovalCheckResult(boolean approvalNeeded, String agentId, String matchedRule, String reason) {
        public static ApprovalCheckResult noApprovalNeeded() {
            return new ApprovalCheckResult(false, null, null, null);
        }
        public static ApprovalCheckResult approvalNeeded(String agentId, String matchedRule, String reason) {
            return new ApprovalCheckResult(true, agentId, matchedRule, reason);
        }
    }
}
