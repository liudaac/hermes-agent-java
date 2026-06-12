package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Organization-native tools that make AI agents aware of their
 * organizational context — teammates, knowledge base, delegation,
 * and escalation.
 *
 * <p>These tools transform agents from isolated workers into
 * first-class organization members by giving them:
 * <ul>
 *   <li>Team awareness (who else is in the org, what can they do?)</li>
 *   <li>Delegation (hand off work to better-suited teammates)</li>
 *   <li>Knowledge access (query the org knowledge base)</li>
 *   <li>Escalation (know when to involve humans)</li>
 * </ul>
 *
 * <p>Actual execution is handled by {@link com.nousresearch.hermes.tools.TenantAwareToolDispatcher},
 * which has access to the per-tenant {@link com.nousresearch.hermes.tenant.core.TenantContext}.
 */
public class OrgNativeTools {
    private static final Logger logger = LoggerFactory.getLogger(OrgNativeTools.class);

    public static void register(ToolRegistry registry) {
        // —— find_teammate ——
        registry.register(new ToolEntry.Builder()
            .name("find_teammate")
            .toolset("organization")
            .emoji("👥")
            .description("Find teammates in your organization by skill, role, or capability. Use this when you need help from someone with specific expertise.")
            .schema(Map.of(
                "description", "Search for teammates (other AI agents) in your organization by their skills, role, or capabilities",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "skill", Map.of("type", "string", "description", "Skill to search for, e.g. 'python', 'security', 'deployment'"),
                        "role", Map.of("type", "string", "description", "Role to filter by, e.g. 'code-reviewer', 'release-manager'"),
                        "level", Map.of("type", "string", "enum", List.of("JUNIOR", "MID", "SENIOR", "LEAD"), "description", "Filter by seniority level")
                    ),
                    "required", List.of()
                )
            ))
            .handler(OrgNativeTools::teammateStub)  // handled by TenantAwareToolDispatcher
            .risk(com.nousresearch.hermes.approval.ToolRisk.NONE)
            .build());

        // —— delegate_task ——
        registry.register(new ToolEntry.Builder()
            .name("delegate_task")
            .toolset("organization")
            .emoji("🔄")
            .description("Delegate a task to another teammate. Use this when someone else has the right skills for a subtask.")
            .schema(Map.of(
                "description", "Delegate a task to a specific teammate (agent) in your organization",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "to", Map.of("type", "string", "description", "The agent ID to delegate to (use find_teammate first)"),
                        "task", Map.of("type", "string", "description", "The task description — be specific about what you need"),
                        "context", Map.of("type", "string", "description", "Additional context the teammate needs to complete the task"),
                        "timeout_seconds", Map.of("type", "integer", "description", "How long to wait for a response (default 120)")
                    ),
                    "required", List.of("to", "task")
                )
            ))
            .handler(OrgNativeTools::delegateStub)  // handled by TenantAwareToolDispatcher
            .risk(com.nousresearch.hermes.approval.ToolRisk.LOW)
            .build());

        // —— query_org_knowledge ——
        registry.register(new ToolEntry.Builder()
            .name("query_org_knowledge")
            .toolset("organization")
            .emoji("📚")
            .description("Search your organization's knowledge base for SOPs, best practices, and shared expertise.")
            .schema(Map.of(
                "description", "Search the organizational knowledge base for relevant documents, SOPs, and shared knowledge",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Search query — what topic or question are you looking for?"),
                        "type", Map.of("type", "string", "enum", List.of("any", "SOP", "DECISION", "LESSON", "FAQ", "POLICY", "REFERENCE", "INSIGHT"), "description", "Filter by entry type (default: any)"),
                        "max_results", Map.of("type", "integer", "description", "Maximum number of results (default 5)")
                    ),
                    "required", List.of("query")
                )
            ))
            .handler(OrgNativeTools::knowledgeStub)  // handled by TenantAwareToolDispatcher
            .risk(com.nousresearch.hermes.approval.ToolRisk.NONE)
            .build());

        // —— escalate_to_human ——
        registry.register(new ToolEntry.Builder()
            .name("escalate_to_human")
            .toolset("organization")
            .emoji("🆘")
            .description("Escalate a decision or approval to a human when you've reached your authority boundary.")
            .schema(Map.of(
                "description", "Escalate a task or decision to a human reviewer for approval or guidance",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "summary", Map.of("type", "string", "description", "Brief summary of what needs human attention"),
                        "detail", Map.of("type", "string", "description", "Detailed explanation — include all relevant context"),
                        "priority", Map.of("type", "string", "enum", List.of("LOW", "NORMAL", "HIGH", "CRITICAL"), "description", "How urgent is this? (default NORMAL)"),
                        "target", Map.of("type", "string", "description", "Specific person or role to escalate to (optional)")
                    ),
                    "required", List.of("summary", "detail")
                )
            ))
            .handler(OrgNativeTools::escalateStub)  // handled by TenantAwareToolDispatcher
            .risk(com.nousresearch.hermes.approval.ToolRisk.MEDIUM)
            .requiresApproval(false)  // escalation itself doesn't need approval
            .build());

        // —— team_post ——
        registry.register(new ToolEntry.Builder()
            .name("team_post")
            .toolset("organization")
            .emoji("📌")
            .description("Post a note to your team's shared state. Visible to all team members via team_read.")
            .schema(Map.of(
                "description", "Post a note to the team's shared state for all members to see",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "key", Map.of("type", "string", "description", "Key for the note (e.g. 'decision:auth-approach', 'finding:race-condition')"),
                        "content", Map.of("type", "string", "description", "Note content"),
                        "tag", Map.of("type", "string", "description", "Optional category tag (e.g. 'decision', 'finding', 'warning')")
                    ),
                    "required", List.of("key", "content")
                )
            ))
            .handler(OrgNativeTools::teamPostStub)
            .risk(com.nousresearch.hermes.approval.ToolRisk.NONE)
            .build());

        // —— team_read ——
        registry.register(new ToolEntry.Builder()
            .name("team_read")
            .toolset("organization")
            .emoji("📖")
            .description("Read your team's shared state. Use this to see what your teammates have shared.")
            .schema(Map.of(
                "description", "Read the team's shared state — notes posted by team members",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "key_pattern", Map.of("type", "string", "description", "Optional: filter by key prefix (e.g. 'decision:' to see only decisions)"),
                        "limit", Map.of("type", "integer", "description", "Maximum entries to return (default 20)")
                    ),
                    "required", List.of()
                )
            ))
            .handler(OrgNativeTools::teamReadStub)
            .risk(com.nousresearch.hermes.approval.ToolRisk.NONE)
            .build());

        // —— team_status ——
        registry.register(new ToolEntry.Builder()
            .name("team_status")
            .toolset("organization")
            .emoji("🏢")
            .description("Get the current status of your team: members, mission, recent activity.")
            .schema(Map.of(
                "description", "Get the current status of your team",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of()
                )
            ))
            .handler(OrgNativeTools::teamStatusStub)
            .risk(com.nousresearch.hermes.approval.ToolRisk.NONE)
            .build());
        // —— orchestrate_intent (第四刀：自我组织) ——
        registry.register(new ToolEntry.Builder()
            .name("orchestrate_intent")
            .toolset("organization")
            .emoji("🎯")
            .description("Decompose a complex task into subtasks, auto-match teammates, and execute the plan. Use this for multi-step work that benefits from self-organization.")
            .schema(Map.of(
                "description", "Intent-driven task orchestration: break down a task, match teammates by skills, execute with auto-reassignment on failure",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "intent", Map.of("type", "string", "description", "The high-level task/intent. Can be a single goal or a multi-step plan (e.g. 'review code and run tests, then deploy')"),
                        "mode", Map.of("type", "string", "enum", List.of("plan", "execute"), "description", "plan = show who would do what without executing; execute = run the plan (default: execute)"),
                        "preferred_team_id", Map.of("type", "string", "description", "Optional team ID to prefer when matching equally capable agents"),
                        "team_id", Map.of("type", "string", "description", "Alias for preferred_team_id"),
                        "allow_delegation", Map.of("type", "boolean", "description", "When true, return advisory delegation recommendations if context pressure signals show main-agent risk"),
                        "context_signals", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Context pressure signals such as compacted, critical_path, near_limit, long_running, high_complexity")
                    ),
                    "required", List.of("intent")
                )
            ))
            .handler(OrgNativeTools::orchestrateStub)
            .risk(com.nousresearch.hermes.approval.ToolRisk.LOW)
            .build());

        // —— intent_status (第四刀：跟踪运行状态) ——
        registry.register(new ToolEntry.Builder()
            .name("intent_status")
            .toolset("organization")
            .emoji("📊")
            .description("Check the status of a previously-started intent orchestration run.")
            .schema(Map.of(
                "description", "Check the status of an intent orchestration run",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "run_id", Map.of("type", "string", "description", "The run ID returned by orchestrate_intent")
                    ),
                    "required", List.of("run_id")
                )
            ))
            .handler(OrgNativeTools::intentStatusStub)
            .risk(com.nousresearch.hermes.approval.ToolRisk.NONE)
            .build());

        // —— org_traces (第五刀：可观测性——查询追踪) ——
        registry.register(new ToolEntry.Builder()
            .name("org_traces")
            .toolset("organization")
            .emoji("🔍")
            .description("Query recent agent traces for forensics and debugging. Use this to understand what other agents (or you) have been doing.")
            .schema(Map.of(
                "description", "Query recent agent execution traces",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "agent_id", Map.of("type", "string", "description", "Optional: filter to a specific agent (default: all)"),
                        "limit", Map.of("type", "integer", "description", "Maximum traces to return (default 10)")
                    ),
                    "required", List.of()
                )
            ))
            .handler(OrgNativeTools::tracesStub)
            .risk(com.nousresearch.hermes.approval.ToolRisk.NONE)
            .build());

        // —— browser_bridge (下一阶段：真实浏览器执行层抽象) ——
        registry.register(new ToolEntry.Builder()
            .name("browser_bridge")
            .toolset("organization")
            .emoji("🌉")
            .description("Execute a browser action through the tenant BrowserBridge. Supports mock, skill-backed Kimi WebBridge discovery, Hermes HTTP-contract bridges, OpenClaw Relay, and Playwright-style adapters.")
            .schema(Map.of(
                "description", "Provider-neutral browser automation action. Use for web tasks that need a real or bridged browser session.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "action", Map.of("type", "string", "enum", List.of("open", "observe", "click", "type", "extract", "scroll", "press", "submit", "close"), "description", "Browser action to perform"),
                        "session_id", Map.of("type", "string", "description", "Browser session id returned by open; omitted uses latest mock session when available"),
                        "url", Map.of("type", "string", "description", "http(s) URL for open"),
                        "target", Map.of("type", "string", "description", "Natural-language target or selector, e.g. 'Search box' or '#submit'"),
                        "text", Map.of("type", "string", "description", "Text to type or send"),
                        "instruction", Map.of("type", "string", "description", "Extraction or action instruction"),
                        "actor", Map.of("type", "string", "description", "Operator/agent identity for audit"),
                        "reason", Map.of("type", "string", "description", "Why this browser action is needed"),
                        "confirmed", Map.of("type", "boolean", "description", "Required for sensitive submit/publish/delete/pay actions")
                    ),
                    "required", List.of("action")
                )
            ))
            .handler(OrgNativeTools::browserBridgeStub)
            .risk(com.nousresearch.hermes.approval.ToolRisk.MEDIUM)
            .requiresApproval(false)
            .build());

        // —— org_anomalies (第五刀：可观测性——异常事件) ——
        registry.register(new ToolEntry.Builder()
            .name("org_anomalies")
            .toolset("organization")
            .emoji("🚨")
            .description("Get recent anomaly events detected across the organization (cost spikes, error storms, etc.).")
            .schema(Map.of(
                "description", "Get recent anomaly events from the org observability layer",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "limit", Map.of("type", "integer", "description", "Maximum events to return (default 10)")
                    ),
                    "required", List.of()
                )
            ))
            .handler(OrgNativeTools::anomaliesStub)
            .risk(com.nousresearch.hermes.approval.ToolRisk.NONE)
            .build());

        logger.info("Registered 12 org-native tools: find_teammate, delegate_task, query_org_knowledge, escalate_to_human, team_post, team_read, team_status, orchestrate_intent, intent_status, org_traces, org_anomalies, browser_bridge");
    }

    // Stub handlers — actual execution is in TenantAwareToolDispatcher
    private static String teammateStub(Map<String, Object> args) {
        return "[org] find_teammate should be dispatched by TenantAwareToolDispatcher";
    }

    private static String delegateStub(Map<String, Object> args) {
        return "[org] delegate_task should be dispatched by TenantAwareToolDispatcher";
    }

    private static String knowledgeStub(Map<String, Object> args) {
        return "[org] query_org_knowledge should be dispatched by TenantAwareToolDispatcher";
    }

    private static String escalateStub(Map<String, Object> args) {
        return "[org] escalate_to_human should be dispatched by TenantAwareToolDispatcher";
    }

    private static String teamPostStub(Map<String, Object> args) {
        return "[org] team_post should be dispatched by TenantAwareToolDispatcher";
    }

    private static String teamReadStub(Map<String, Object> args) {
        return "[org] team_read should be dispatched by TenantAwareToolDispatcher";
    }

    private static String teamStatusStub(Map<String, Object> args) {
        return "[org] team_status should be dispatched by TenantAwareToolDispatcher";
    }

    private static String orchestrateStub(Map<String, Object> args) {
        return "[org] orchestrate_intent should be dispatched by TenantAwareToolDispatcher";
    }

    private static String intentStatusStub(Map<String, Object> args) {
        return "[org] intent_status should be dispatched by TenantAwareToolDispatcher";
    }

    private static String tracesStub(Map<String, Object> args) {
        return "[org] org_traces should be dispatched by TenantAwareToolDispatcher";
    }

    private static String anomaliesStub(Map<String, Object> args) {
        return "[org] org_anomalies should be dispatched by TenantAwareToolDispatcher";
    }

    private static String browserBridgeStub(Map<String, Object> args) {
        return "[org] browser_bridge should be dispatched by TenantAwareToolDispatcher";
    }
}
