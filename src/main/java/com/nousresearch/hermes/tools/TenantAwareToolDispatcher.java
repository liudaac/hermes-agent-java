package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.plugin.PluginManager;
import com.nousresearch.hermes.plugin.hook.HookEngine;
import com.nousresearch.hermes.plugin.hook.HookType;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.sandbox.ProcessOptions;
import com.nousresearch.hermes.tenant.sandbox.ProcessResult;
import com.nousresearch.hermes.tenant.sandbox.TenantFileSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FIXED: Tenant-aware tool dispatcher that routes all tool calls through tenant sandbox.
 */
import com.nousresearch.hermes.approval.ApprovalMessageHandler;
import com.nousresearch.hermes.browser.BrowserAction;
import com.nousresearch.hermes.browser.BrowserBridgePolicy;
import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.tenant.audit.AuditEvent;
import com.nousresearch.hermes.approval.ApprovalResult;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.collaboration.Negotiator;

public class TenantAwareToolDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(TenantAwareToolDispatcher.class);
    private static final int MAX_SEARCH_RESULTS = 100;
    
    private final TenantContext tenantContext;
    private final ToolRegistry globalRegistry;
    
    // Per-tenant approval system (NOT shared across tenants)
    private ApprovalSystem approvalSystem;
    private ApprovalMessageHandler approvalMessageHandler;
    
    // AI原生组织：结构化协商引擎
    private Negotiator negotiator;
    
    public TenantAwareToolDispatcher(TenantContext tenantContext, ToolRegistry globalRegistry) {
        this.tenantContext = tenantContext;
        this.globalRegistry = globalRegistry;
    }
    
    public void setApprovalSystem(ApprovalSystem approvalSystem) {
        this.approvalSystem = approvalSystem;
    }
    
    public void setApprovalMessageHandler(ApprovalMessageHandler handler) {
        this.approvalMessageHandler = handler;
    }
    
    public void setNegotiator(Negotiator negotiator) {
        this.negotiator = negotiator;
    }
    
    public String dispatch(String toolName, Map<String, Object> args) {
        Map<String, Object> safeArgs = args != null ? args : Map.of();
        logger.debug("Dispatching tool: {} for tenant: {}", toolName, tenantContext.getTenantId());

        // --- Plugin hook: pre_tool_call ---
        HookEngine hookEngine = getHookEngine();
        if (hookEngine != null) {
            Optional<String> blocked = hookEngine.checkToolBlocked(
                    toolName, safeArgs,
                    tenantContext.getTenantId(),  // task_id (reused as tenant_id)
                    tenantContext.getTenantId(),  // session_id (reused as tenant_id)
                    ""  // tool_call_id not available at this layer
            );
            if (blocked.isPresent()) {
                logger.info("Tool '{}' blocked by plugin hook: {}", toolName, blocked.get());
                String result = ToolRegistry.toolError("Blocked by policy: " + blocked.get());
                tenantContext.getToolRegistry().recordToolCall(toolName, safeArgs, result);
                return result;
            }
        }

        var permission = tenantContext.getToolRegistry().checkPermission(toolName, safeArgs);
        if (!permission.isAllowed()) {
            return ToolRegistry.toolError(permission.getReason());
        }

        // Compute tool entry once for both approval and negotiation
        var optEntry = globalRegistry.getAllTools().stream()
            .filter(t -> t.getName().equals(toolName))
            .findFirst();

        // Per-tenant approval check BEFORE execution
        if (approvalSystem != null) {
            if (optEntry.isPresent() && optEntry.get().requiresApproval()) {
                var entry = optEntry.get();
                String operation = entry.getApprovalMessageTemplate();
                if (operation == null || operation.isEmpty()) {
                    operation = toolName + " with args: " + safeArgs;
                } else {
                    for (var ae : safeArgs.entrySet()) {
                        String val = String.valueOf(ae.getValue());
                        if (val.length() > 100) val = val.substring(0, 97) + "...";
                        operation = operation.replace("{" + ae.getKey() + "}", val);
                    }
                }
                String details = "Tenant: " + tenantContext.getTenantId()
                    + ", Tool: " + toolName + " (risk: " + entry.getRisk() + ")";
                
                approvalSystem.setMode(entry.getApprovalType(), entry.getRisk().toDefaultMode());
                
                if (approvalMessageHandler != null) {
                    final String op = operation;
                    approvalSystem.setExternalApprover(request ->
                        approvalMessageHandler.registerRequest(tenantContext.getTenantId(),
                            (com.nousresearch.hermes.approval.ApprovalRequest) request));
                }
                
                ApprovalResult approval = approvalSystem.requestApproval(
                    entry.getApprovalType(), operation, details);
                
                if (!approval.isApproved()) {
                    String denied = ToolRegistry.toolError("Approval denied: " + approval.getReason());
                    tenantContext.getToolRegistry().recordToolCall(toolName, safeArgs, denied);
                    return denied;
                }
            }
        }

        // AI原生组织：高风险工具自动协商
        if (negotiator != null && optEntry.isPresent()) {
            var entry = optEntry.get();
            if (entry.getRisk().ordinal() >= com.nousresearch.hermes.approval.ToolRisk.MEDIUM.ordinal()) {
                double confidence = entry.getRisk() == com.nousresearch.hermes.approval.ToolRisk.HIGH ? 0.5 : 0.8;
                var negResult = negotiator.autoNegotiate(
                    tenantContext.getTenantId(), toolName, 
                    toolName + " with args: " + safeArgs.keySet(), confidence);
                if (negResult.needsHuman()) {
                    String denied = ToolRegistry.toolError("Escalated for review: " + negResult.detail);
                    tenantContext.getToolRegistry().recordToolCall(toolName, safeArgs, denied);
                    return denied;
                }
            }
        }

        String result;
        try {
            result = switch (toolName) {
                case "read_file", "write_file", "list_directory", "search_files",
                     "file_read", "file_write", "file_list" -> dispatchFileTool(toolName, safeArgs);
                case "execute_python", "execute_javascript", "execute_bash" -> dispatchCodeTool(toolName, safeArgs);
                case "terminal", "execute_command" -> dispatchTerminalTool(toolName, safeArgs);
                case "memory_read", "memory_write", "memory_search" -> dispatchMemoryTool(toolName, safeArgs);
                case "find_teammate", "delegate_task", "query_org_knowledge", "escalate_to_human", "team_post", "team_read", "team_status", "orchestrate_intent", "intent_status", "org_traces", "org_anomalies", "browser_bridge" -> dispatchOrgTool(toolName, safeArgs);
                default -> dispatchGenericTool(toolName, safeArgs);
            };
        } catch (Exception e) {
            logger.error("Error dispatching tool: {}", toolName, e);
            result = ToolRegistry.toolError("Tool execution failed: " + e.getMessage());
        }

        // --- Plugin hook: post_tool_call ---
        if (hookEngine != null) {
            Map<String, Object> postCtx = new HashMap<>();
            postCtx.put("tool_name", toolName);
            postCtx.put("args", safeArgs);
            postCtx.put("result", result);
            postCtx.put("task_id", tenantContext.getTenantId());
            postCtx.put("session_id", tenantContext.getTenantId());
            hookEngine.invoke(HookType.POST_TOOL_CALL, postCtx);
        }

        // --- Plugin hook: transform_tool_result ---
        if (hookEngine != null) {
            Map<String, Object> transformCtx = new HashMap<>();
            transformCtx.put("tool_name", toolName);
            transformCtx.put("args", safeArgs);
            transformCtx.put("result", result);
            transformCtx.put("task_id", tenantContext.getTenantId());
            transformCtx.put("session_id", tenantContext.getTenantId());
            List<Object> transforms = hookEngine.invoke(HookType.TRANSFORM_TOOL_RESULT, transformCtx);
            for (Object t : transforms) {
                if (t instanceof String s && !s.isEmpty()) {
                    result = s;
                    logger.debug("Tool result transformed by plugin for '{}'", toolName);
                }
            }
        }

        tenantContext.getToolRegistry().recordToolCall(toolName, safeArgs, result);
        return result;
    }
    
    private String dispatchFileTool(String toolName, Map<String, Object> args) {
        TenantFileSandbox sandbox = tenantContext.getFileSandbox();
        
        try {
            switch (toolName) {
                case "read_file", "file_read": {
                    String pathStr = (String) args.get("path");
                    var validation = validateTenantPath(pathStr, TenantFileSandbox.AccessMode.READ);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path path = validation.path();
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    int offset = ((Number) args.getOrDefault("offset", 1)).intValue();
                    int limit = ((Number) args.getOrDefault("limit", 1000)).intValue();
                    List<String> lines = content.isEmpty() ? List.of() : content.lines().toList();
                    int start = Math.max(0, offset - 1);
                    int end = Math.min(lines.size(), start + limit);
                    String returned = start < end ? String.join("\n", lines.subList(start, end)) : "";
                    return ToolRegistry.toolResult(Map.of(
                        "path", sandbox.getSandboxRoot().relativize(path).toString(),
                        "content", returned,
                        "total_lines", lines.size(),
                        "returned_lines", Math.max(0, end - start),
                        "offset", start + 1,
                        "truncated", end < lines.size()
                    ));
                }
                case "write_file", "file_write": {
                    String pathStr = (String) args.get("path");
                    var validation = validateTenantPath(pathStr, TenantFileSandbox.AccessMode.WRITE);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path path = validation.path();
                    String content = (String) args.getOrDefault("content", "");
                    boolean append = Boolean.TRUE.equals(args.get("append"));
                    Path parent = path.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    if (append) {
                        Files.writeString(path, content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } else {
                        Files.writeString(path, content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    }
                    return ToolRegistry.toolResult(Map.of(
                        "path", sandbox.getSandboxRoot().relativize(path).toString(),
                        "bytes_written", content.getBytes(StandardCharsets.UTF_8).length,
                        "append", append,
                        "success", true,
                        "status", append ? "appended" : "written"
                    ));
                }
                case "list_directory", "file_list": {
                    String pathStr = (String) args.getOrDefault("path", ".");
                    var validation = validateTenantPath(pathStr, TenantFileSandbox.AccessMode.READ);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path path = validation.path();
                    if (!Files.isDirectory(path)) {
                        return ToolRegistry.toolError("Not a directory: " + pathStr);
                    }
                    List<Map<String, Object>> entries = new ArrayList<>();
                    try (var stream = Files.list(path)) {
                        stream.forEach(p -> {
                            try {
                                Map<String, Object> entry = new HashMap<>();
                                entry.put("name", p.getFileName().toString());
                                entry.put("path", sandbox.getSandboxRoot().relativize(p).toString());
                                entry.put("is_directory", Files.isDirectory(p));
                                entry.put("is_file", Files.isRegularFile(p));
                                entry.put("size", Files.size(p));
                                entry.put("last_modified", Files.getLastModifiedTime(p).toString());
                                entries.add(entry);
                            } catch (IOException e) {
                                logger.debug("Could not read directory entry: {}", p, e);
                            }
                        });
                    }
                    return ToolRegistry.toolResult(Map.of(
                        "path", sandbox.getSandboxRoot().relativize(path).toString(),
                        "entries", entries,
                        "files", entries.stream().map(e -> e.get("name")).toList(),
                        "count", entries.size()
                    ));
                }
                case "search_files": {
                    String pattern = (String) args.get("pattern");
                    if (pattern == null || pattern.isBlank()) {
                        return ToolRegistry.toolError("Search pattern is required");
                    }
                    String pathStr = (String) args.getOrDefault("path", ".");
                    var validation = validateTenantPath(pathStr, TenantFileSandbox.AccessMode.READ);
                    if (!validation.isAllowed()) {
                        return ToolRegistry.toolError("Access denied: " + validation.getReason());
                    }
                    Path root = validation.path();
                    List<String> results = new ArrayList<>();
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (FileSystems.getDefault().getPathMatcher("glob:" + pattern)
                                    .matches(Paths.get(file.getFileName().toString()))) {
                                results.add(sandbox.getSandboxRoot().relativize(file).toString());
                                if (results.size() >= MAX_SEARCH_RESULTS) {
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    return ToolRegistry.toolResult(Map.of(
                        "pattern", pattern,
                        "path", sandbox.getSandboxRoot().relativize(root).toString(),
                        "results", results,
                        "count", results.size(),
                        "truncated", results.size() >= MAX_SEARCH_RESULTS
                    ));
                }
                default:
                    return ToolRegistry.toolError("Unknown file tool: " + toolName);
            }
        } catch (IOException e) {
            return ToolRegistry.toolError("File operation failed: " + e.getMessage());
        }
    }
    
    private String dispatchCodeTool(String toolName, Map<String, Object> args) {
        String code = (String) args.get("code");
        if (code == null) {
            return ToolRegistry.toolError("Code is required");
        }

        String command = switch (toolName) {
            case "execute_python" -> "python3";
            case "execute_javascript" -> "node";
            case "execute_bash" -> "/bin/bash";
            default -> throw new IllegalArgumentException("Unknown code tool: " + toolName);
        };
        List<String> execCommand = "execute_bash".equals(toolName)
            ? List.of(command, "-c", code)
            : List.of(command, "-c", code);

        int timeout = ((Number) args.getOrDefault("timeout", 60)).intValue();
        long memoryLimitMb = ((Number) args.getOrDefault("memory_limit_mb", 512)).longValue();
        return executeInTenantSandbox(execCommand, timeout, memoryLimitMb);
    }
    
    private String dispatchTerminalTool(String toolName, Map<String, Object> args) {
        String command = (String) args.get("command");
        if (command == null || command.isBlank()) {
            return ToolRegistry.toolError("Command is required");
        }

        String cwd = (String) args.get("cwd");
        int timeout = ((Number) args.getOrDefault("timeout", 300)).intValue();
        var validation = validateTenantPath(cwd == null || cwd.isBlank() ? "." : cwd,
            TenantFileSandbox.AccessMode.READ);
        if (!validation.isAllowed()) {
            return ToolRegistry.toolError("Access denied: " + validation.getReason());
        }

        return executeInTenantSandbox(List.of("/bin/bash", "-c", command), timeout, 512,
            validation.path());
    }
    
    private String dispatchMemoryTool(String toolName, Map<String, Object> args) {
        var memoryManager = tenantContext.getMemoryManager();
        
        return switch (toolName) {
            case "memory_read" -> {
                int limit = ((Number) args.getOrDefault("limit", 10)).intValue();
                var memories = memoryManager.search((String) args.get("query"), limit);
                yield ToolRegistry.toolResult(Map.of("memories", memories));
            }
            case "memory_write" -> {
                boolean added = memoryManager.addMemory((String) args.get("content"));
                yield ToolRegistry.toolResult(Map.of("status", added ? "saved" : "failed"));
            }
            case "memory_search" -> {
                int limit = ((Number) args.getOrDefault("limit", 10)).intValue();
                var results = memoryManager.search((String) args.get("query"), limit);
                yield ToolRegistry.toolResult(Map.of("results", results));
            }
            default -> ToolRegistry.toolError("Unknown memory tool: " + toolName);
        };
    }
    
    // ========== AI原生组织工具 ==========
    private String dispatchOrgTool(String toolName, Map<String, Object> args) {
        return switch (toolName) {
            case "find_teammate" -> findTeammate(args);
            case "delegate_task" -> delegateTask(args);
            case "query_org_knowledge" -> queryOrgKnowledge(args);
            case "escalate_to_human" -> escalateToHuman(args);
            case "team_post" -> teamPost(args);
            case "team_read" -> teamRead(args);
            case "team_status" -> teamStatus(args);
            case "orchestrate_intent" -> orchestrateIntent(args);
            case "intent_status" -> intentStatus(args);
            case "org_traces" -> orgTraces(args);
            case "org_anomalies" -> orgAnomalies(args);
            case "browser_bridge" -> browserBridge(args);
            default -> ToolRegistry.toolError("Unknown org tool: " + toolName);
        };
    }

    /**
     * Find teammates by skill, role, or capability.
     * Searches the tenant's agent role registry.
     */
    private String findTeammate(Map<String, Object> args) {
        String skill = (String) args.get("skill");
        String role = (String) args.get("role");
        String level = (String) args.get("level");

        List<Map<String, Object>> results = new ArrayList<>();
        for (var entry : tenantContext.listAgentRoles().entrySet()) {
            var agentRole = entry.getValue();
            boolean match = true;

            if (skill != null && !skill.isBlank()) {
                match = agentRole.getSkills().stream()
                    .anyMatch(s -> s.toLowerCase().contains(skill.toLowerCase()));
            }
            if (match && role != null && !role.isBlank()) {
                match = agentRole.getRoleName().toLowerCase().contains(role.toLowerCase());
            }
            if (match && level != null && !level.isBlank()) {
                match = agentRole.getLevel().name().equalsIgnoreCase(level);
            }

            if (match) {
                results.add(agentRole.toMap());
            }
        }

        return ToolRegistry.toolResult(Map.of(
            "teammates", results,
            "count", results.size(),
            "tenant", tenantContext.getTenantId()
        ));
    }

    /**
     * Delegate a task to another agent via TenantBus.
     * Sends a request message and waits for a reply.
     */
    private String delegateTask(Map<String, Object> args) {
        String to = (String) args.get("to");
        String task = (String) args.get("task");
        String context = (String) args.getOrDefault("context", "");
        int timeoutSec = ((Number) args.getOrDefault("timeout_seconds", 120)).intValue();

        if (to == null || to.isBlank()) {
            return ToolRegistry.toolError("Target agent 'to' is required");
        }
        if (task == null || task.isBlank()) {
            return ToolRegistry.toolError("Task description is required");
        }

        // Ensure collaboration is initialized
        tenantContext.initCollaboration();
        var bus = tenantContext.getTenantBus();

        if (!bus.isRegistered(to)) {
            return ToolRegistry.toolError("Teammate '" + to + "' is not available. Use find_teammate to see who's online.");
        }

        try {
            var msg = com.nousresearch.hermes.collaboration.AgentMessage.builder(
                    tenantContext.getTenantId(), to, com.nousresearch.hermes.collaboration.AgentMessage.Type.REQUEST)
                .action("delegate_task")
                .payload(Map.of(
                    "task", task,
                    "context", context,
                    "from", tenantContext.getTenantId()
                ))
                .timeoutMs(timeoutSec * 1000L)
                .build();

            var reply = bus.sendAndWait(msg, timeoutSec * 1000L);
            return ToolRegistry.toolResult(Map.of(
                "delegated_to", to,
                "status", "completed",
                "result", reply.getResultText(),
                "payload", reply.getPayload()
            ));
        } catch (com.nousresearch.hermes.collaboration.TenantBus.TimeoutException e) {
            return ToolRegistry.toolError("Delegation to '" + to + "' timed out after " + timeoutSec + "s. The teammate may be busy.");
        } catch (Exception e) {
            return ToolRegistry.toolError("Delegation failed: " + e.getMessage());
        }
    }

    /**
     * Query the organizational knowledge base.
     * Searches across SOPs, best practices, troubleshooting guides, etc.
     */
    private String queryOrgKnowledge(Map<String, Object> args) {
        String query = (String) args.get("query");
        String typeStr = (String) args.get("type");
        int maxResults = ((Number) args.getOrDefault("max_results", 5)).intValue();

        if (query == null || query.isBlank()) {
            return ToolRegistry.toolError("Search query is required");
        }

        var kb = tenantContext.getOrgKnowledgeBase();
        List<Map<String, Object>> results = new ArrayList<>();

        try {
            var entries = kb.search(query, maxResults);

            // Filter by type if requested
            for (var entry : entries) {
                if (typeStr != null && !"any".equalsIgnoreCase(typeStr)) {
                    if (!entry.getType().name().equalsIgnoreCase(typeStr)) continue;
                }
                results.add(Map.of(
                    "id", entry.getId(),
                    "title", entry.getTitle(),
                    "type", entry.getType().name(),
                    "content", entry.getContent(),
                    "author", entry.getAuthor(),
                    "tags", new ArrayList<>(entry.getTags()),
                    "classification", entry.getClassification().name(),
                    "views", entry.getViewCount()
                ));
                if (results.size() >= maxResults) break;
            }
        } catch (Exception e) {
            logger.warn("Org knowledge search failed: {}", e.getMessage());
        }

        return ToolRegistry.toolResult(Map.of(
            "query", query,
            "results", results,
            "count", results.size(),
            "total_entries", kb.size(),
            "hint", results.isEmpty() ? "No matching knowledge found. Consider adding to the knowledge base." : ""
        ));
    }

    /**
     * Escalate a decision to a human via the HandoffProtocol.
     */
    private String escalateToHuman(Map<String, Object> args) {
        String summary = (String) args.get("summary");
        String detail = (String) args.get("detail");
        String priorityStr = (String) args.getOrDefault("priority", "NORMAL");
        String target = (String) args.get("target");

        if (summary == null || summary.isBlank()) {
            return ToolRegistry.toolError("Summary is required for escalation");
        }
        if (detail == null || detail.isBlank()) {
            return ToolRegistry.toolError("Detail is required for escalation");
        }

        com.nousresearch.hermes.org.handoff.HandoffContext.Priority priority;
        try {
            priority = com.nousresearch.hermes.org.handoff.HandoffContext.Priority.valueOf(priorityStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            priority = com.nousresearch.hermes.org.handoff.HandoffContext.Priority.NORMAL;
        }

        var protocol = tenantContext.getHandoffProtocol();
        protocol.start();

        var ctx = protocol.requestApproval(
            tenantContext.getTenantId(),
            summary,
            detail,
            target != null ? target : "human-operator",
            600
        );

        return ToolRegistry.toolResult(Map.of(
            "handoff_id", ctx.getHandoffId(),
            "status", "pending",
            "priority", priority.name(),
            "summary", summary,
            "next_steps", "A human reviewer will be notified. You can check status with the handoff_id."
        ));
    }

    // ======== 第三刀：Team 操作 ========

    /**
     * Post a note to the agent's team's shared state.
     * All team members can read it via team_read.
     */
    private String teamPost(Map<String, Object> args) {
        String key = (String) args.get("key");
        Object content = args.get("content");
        String tag = (String) args.get("tag");

        if (key == null || key.isBlank()) {
            return ToolRegistry.toolError("Key is required for team_post");
        }
        if (content == null) {
            return ToolRegistry.toolError("Content is required for team_post");
        }

        // Get the agent's team from the dispatcher context
        // Note: dispatcher doesn't know which agent, so we use the tenant's default team for the agent.
        // The proper fix is to plumb the agentId through, but for now use a team key prefix per agent.
        // We'll use the team created by the agent (id starts with team_<agentId> or default team)
        var teamManager = tenantContext.getTeamManager();
        var teams = teamManager.listTeams();
        if (teams.isEmpty()) {
            return ToolRegistry.toolError("No team exists for this tenant");
        }
        // Use the most recently active team (heuristic)
        var team = teams.get(teams.size() - 1);

        var entry = new java.util.LinkedHashMap<String, Object>();
        entry.put("content", content);
        if (tag != null) entry.put("tag", tag);
        entry.put("posted_at", java.time.Instant.now().toString());
        team.putState(key, entry);

        return ToolRegistry.toolResult(Map.of(
            "team", team.getName(),
            "key", key,
            "tag", tag != null ? tag : "",
            "status", "posted"
        ));
    }

    /**
     * Read entries from the agent's team's shared state.
     */
    private String teamRead(Map<String, Object> args) {
        String keyPattern = (String) args.get("key_pattern");
        int limit = ((Number) args.getOrDefault("limit", 20)).intValue();

        var teamManager = tenantContext.getTeamManager();
        var teams = teamManager.listTeams();
        if (teams.isEmpty()) {
            return ToolRegistry.toolError("No team exists for this tenant");
        }
        var team = teams.get(teams.size() - 1);

        var entries = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var e : team.getState().entrySet()) {
            if (keyPattern != null && !keyPattern.isBlank() && !e.getKey().startsWith(keyPattern)) {
                continue;
            }
            if (entries.size() >= limit) break;
            entries.add(java.util.Map.of(
                "key", e.getKey(),
                "value", e.getValue()
            ));
        }
        return ToolRegistry.toolResult(Map.of(
            "team", team.getName(),
            "entries", entries,
            "count", entries.size(),
            "filter", keyPattern != null ? keyPattern : ""
        ));
    }

    /**
     * Get the current status of the agent's team.
     */
    private String teamStatus(Map<String, Object> args) {
        var teamManager = tenantContext.getTeamManager();
        var teams = teamManager.listTeams();
        if (teams.isEmpty()) {
            return ToolRegistry.toolResult(Map.of(
                "total_teams", 0,
                "hint", "No team exists yet. Create one to enable team collaboration."
            ));
        }
        var team = teams.get(teams.size() - 1);

        var recent = team.getRecentActivity(5).stream()
            .map(a -> java.util.Map.of(
                "type", a.type(),
                "actor", a.actor() != null ? a.actor() : "",
                "detail", a.detail(),
                "at", a.timestamp().toString()
            )).toList();

        return ToolRegistry.toolResult(java.util.Map.of(
            "team", team.toMap(),
            "recent_activity", recent
        ));
    }

    // ======== 第四刀：Intent 自我组织 ========

    /**
     * Plan or execute an intent — let the agent self-organize around a complex task.
     * In plan mode, returns who would do what. In execute mode, runs the plan asynchronously.
     */
    private String orchestrateIntent(Map<String, Object> args) {
        String intent = (String) args.get("intent");
        String mode = (String) args.getOrDefault("mode", "execute");

        if (intent == null || intent.isBlank()) {
            return ToolRegistry.toolError("Intent is required");
        }

        try {
            var orchestrator = tenantContext.getIntentOrchestrator();

            if ("plan".equalsIgnoreCase(mode)) {
                var plan = orchestrator.plan(intent);
                return ToolRegistry.toolResult(plan.toMap());
            } else {
                var run = orchestrator.execute(intent);
                return ToolRegistry.toolResult(java.util.Map.of(
                    "run_id", run.runId,
                    "intent", run.intent,
                    "status", run.status.name(),
                    "subtasks_total", run.assignments.size(),
                    "hint", "Use intent_status(run_id) to check progress"
                ));
            }
        } catch (Exception e) {
            return ToolRegistry.toolError("Orchestration failed: " + e.getMessage());
        }
    }

    /**
     * Get the status of a previously-started intent run.
     */
    private String intentStatus(Map<String, Object> args) {
        String runId = (String) args.get("run_id");
        if (runId == null || runId.isBlank()) {
            return ToolRegistry.toolError("run_id is required");
        }

        try {
            var run = tenantContext.getIntentOrchestrator().getRun(runId);
            if (run == null) {
                return ToolRegistry.toolError("No run found with id: " + runId);
            }
            return ToolRegistry.toolResult(run.toMap());
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed to get run status: " + e.getMessage());
        }
    }

    // ======== 第五刀：可观测性 API ========

    /**
     * Get recent agent traces for forensics.
     */
    private String orgTraces(Map<String, Object> args) {
        String agentId = (String) args.get("agent_id");
        int limit = ((Number) args.getOrDefault("limit", 10)).intValue();

        try {
            var obs = tenantContext.getObservability();
            var traces = agentId != null && !agentId.isBlank()
                ? obs.getRecentTraces(agentId, limit)
                : obs.getAllRecentTraces(limit);

            var entries = new java.util.ArrayList<java.util.Map<String, Object>>();
            for (var t : traces) {
                entries.add(java.util.Map.of(
                    "trace_id", t.getTraceId(),
                    "agent", t.getAgentId(),
                    "session", t.getSessionId(),
                    "task", t.getTaskDescription(),
                    "status", t.getStatus().name(),
                    "steps", t.stepCount(),
                    "errors", t.getErrorCount(),
                    "duration_ms", t.getEndTime() != null
                        ? java.time.Duration.between(t.getStartTime(), t.getEndTime()).toMillis()
                        : 0,
                    "started_at", t.getStartTime().toString()
                ));
            }
            return ToolRegistry.toolResult(java.util.Map.of(
                "traces", entries,
                "count", entries.size(),
                "filter", agentId != null ? agentId : "all"
            ));
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed to get traces: " + e.getMessage());
        }
    }

    /**
     * Get recent anomaly events.
     */
    private String orgAnomalies(Map<String, Object> args) {
        int limit = ((Number) args.getOrDefault("limit", 10)).intValue();

        try {
            var obs = tenantContext.getObservability();
            var events = obs.getRecentAnomalies(limit);
            var entries = events.stream().map(a -> java.util.Map.of(
                "type", a.type().name(),
                "agent", a.agentId(),
                "message", a.message(),
                "at", a.time().toString()
            )).toList();
            return ToolRegistry.toolResult(java.util.Map.of(
                "anomalies", entries,
                "count", entries.size()
            ));
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed to get anomalies: " + e.getMessage());
        }
    }

    /**
     * Execute a provider-neutral browser action through the tenant BrowserBridge.
     */
    private String browserBridge(Map<String, Object> args) {
        BrowserAction action = BrowserAction.from(args);
        BrowserBridgePolicy.Decision decision = new BrowserBridgePolicy().check(action, args);
        String actor = action.actor() != null ? action.actor() : "agent";
        String reason = action.reason() != null ? action.reason() : "";

        if (!decision.allowed()) {
            var approval = decision.requiresConfirmation()
                ? tenantContext.getBrowserApprovalQueue().create(action, args, decision.reason())
                : null;
            tenantContext.getAuditLogger().log(AuditEvent.CONTROL_BROWSER_ACTION_DENIED, java.util.Map.of(
                "tenantId", tenantContext.getTenantId(),
                "actor", actor,
                "action", action.action(),
                "target", action.target() != null ? action.target() : "",
                "url", action.url() != null ? action.url() : "",
                "reason", reason,
                "denyReason", decision.reason(),
                "requiresConfirmation", decision.requiresConfirmation(),
                "approvalId", approval != null ? approval.id() : "",
                "timestamp", System.currentTimeMillis()
            ));
            if (approval != null) {
                tenantContext.getAuditLogger().log(AuditEvent.CONTROL_BROWSER_APPROVAL_REQUESTED, java.util.Map.of(
                    "tenantId", tenantContext.getTenantId(),
                    "actor", actor,
                    "approvalId", approval.id(),
                    "action", action.action(),
                    "url", action.url() != null ? action.url() : "",
                    "target", action.target() != null ? action.target() : "",
                    "reason", reason,
                    "denyReason", decision.reason(),
                    "timestamp", System.currentTimeMillis()
                ));
            }
            java.util.Map<String, Object> errorPayload = new java.util.LinkedHashMap<>();
            errorPayload.put("requires_confirmation", decision.requiresConfirmation());
            if (approval != null) {
                errorPayload.put("approval_id", approval.id());
                errorPayload.put("approval_status", approval.status().name());
            }
            return ToolRegistry.toolError(decision.reason(), errorPayload);
        }

        var obs = tenantContext.getObservability();
        var trace = obs.startTrace("browser-bridge", tenantContext.getTenantId(), "browser_bridge:" + action.action());
        long started = System.currentTimeMillis();
        trace.meta("browser_action", action.action())
            .meta("actor", actor)
            .meta("reason", reason)
            .step(AgentTrace.Step.toolCall("browser_bridge", args.toString(), java.util.List.of("manual browser", "provider-specific browser tool"), 0.86, 0, 0));

        var result = tenantContext.getBrowserBridge().execute(action);
        long duration = System.currentTimeMillis() - started;
        trace.step(AgentTrace.Step.toolResult("browser_bridge", result.toMap().toString(), duration));
        if (!result.ok()) {
            trace.step(AgentTrace.Step.error(result.message()));
        }
        trace.end(result.ok() ? AgentTrace.Status.SUCCESS : AgentTrace.Status.FAILED);
        obs.completeTrace(trace);

        tenantContext.getAuditLogger().log(AuditEvent.CONTROL_BROWSER_ACTION, java.util.Map.of(
            "tenantId", tenantContext.getTenantId(),
            "actor", actor,
            "action", action.action(),
            "sessionId", result.sessionId() != null ? result.sessionId() : (action.sessionId() != null ? action.sessionId() : ""),
            "url", result.url() != null ? result.url() : (action.url() != null ? action.url() : ""),
            "ok", result.ok(),
            "reason", reason,
            "traceId", trace.getTraceId(),
            "durationMs", duration,
            "timestamp", System.currentTimeMillis()
        ));

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>(result.toMap());
        payload.put("trace_id", trace.getTraceId());
        payload.put("provider", tenantContext.getBrowserBridge().getClass().getSimpleName());
        return result.ok()
            ? ToolRegistry.toolResult(payload)
            : ToolRegistry.toolError(result.message(), payload);
    }

    private String dispatchGenericTool(String toolName, Map<String, Object> args) {
        var entry = globalRegistry.getAllTools().stream()
            .filter(t -> t.getName().equals(toolName))
            .findFirst()
            .orElse(null);
        
        if (entry == null) {
            return ToolRegistry.toolError("Unknown tool: " + toolName);
        }
        
        return entry.getHandler().apply(args);
    }

    private TenantFileSandbox.PathValidationResult validateTenantPath(String pathStr, TenantFileSandbox.AccessMode mode) {
        TenantFileSandbox sandbox = tenantContext.getFileSandbox();
        if (pathStr == null || pathStr.isBlank()) {
            pathStr = ".";
        }

        Path sandboxRoot = sandbox.getSandboxRoot().toAbsolutePath().normalize();
        Path requested = Paths.get(pathStr);
        Path candidate;

        if (requested.isAbsolute()) {
            candidate = requested.toAbsolutePath().normalize();
            if (!candidate.startsWith(sandboxRoot)) {
                return TenantFileSandbox.PathValidationResult.rejected(
                    "absolute path outside tenant sandbox", candidate);
            }
        } else {
            candidate = sandboxRoot.resolve(requested).normalize();
            if (!candidate.startsWith(sandboxRoot)) {
                return TenantFileSandbox.PathValidationResult.rejected(
                    "path traversal outside tenant sandbox", candidate);
            }
        }

        return sandbox.validatePath(candidate.toString(), mode);
    }

    private String executeInTenantSandbox(List<String> command, int timeout, long memoryLimitMb) {
        return executeInTenantSandbox(command, timeout, memoryLimitMb,
            tenantContext.getFileSandbox().getSandboxRoot());
    }

    private String executeInTenantSandbox(List<String> command, int timeout, long memoryLimitMb, Path workDirectory) {
        try {
            ProcessOptions options = ProcessOptions.builder()
                .timeoutSeconds(timeout)
                .maxMemoryMB(memoryLimitMb)
                .workDirectory(workDirectory)
                .redirectErrorStream(false)
                .build();
            ProcessResult result = tenantContext.exec(command, options);

            Map<String, Object> output = new HashMap<>();
            output.put("stdout", result.getStdout() == null ? "" : result.getStdout());
            output.put("stderr", result.getStderr() == null ? "" : result.getStderr());
            output.put("exit_code", result.getExitCode());
            output.put("success", result.isSuccess());
            output.put("timed_out", result.isTimedOut());
            output.put("tenant_id", tenantContext.getTenantId());
            if (result.getError() != null) {
                output.put("error", result.getError());
            }
            return result.isSuccess() ? ToolRegistry.toolResult(output) : ToolRegistry.toolError(
                result.getError() != null ? result.getError() : "Process exited with code " + result.getExitCode(),
                output);
        } catch (Exception e) {
            return ToolRegistry.toolError("Process sandbox execution failed: " + e.getMessage());
        }
    }

    private HookEngine getHookEngine() {
        PluginManager pm = PluginManager.getInstance();
        return pm != null ? pm.getHookEngineFacade() : null;
    }
}
