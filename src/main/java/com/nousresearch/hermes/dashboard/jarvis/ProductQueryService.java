package com.nousresearch.hermes.dashboard.jarvis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.dlq.DeadLetterQueue;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ProductQueryService — turns a classified intent into a real answer by
 * calling the underlying product service.
 *
 * <p>Each product has a small catalog of high-value actions:</p>
 *
 * <ul>
 *   <li><b>portal</b>: list_approvals, list_recent_runs, list_teams,
 *       list_pending_tasks</li>
 *   <li><b>ops</b>: list_tenants, tenant_stats, list_sessions_summary</li>
 *   <li><b>noc</b>: list_dlq, dlq_stats</li>
 * </ul>
 *
 * <p>For each call we ask the LLM to pick an action + extract parameters
 * (entity ids, status filters, limits, etc.) using a structured JSON shape.
 * The picked action is then executed by calling the underlying service
 * directly — no HTTP round-trips, no second-level mocks.</p>
 *
 * <p>Result is a {@link QueryResult} that carries:</p>
 * <ul>
 *   <li>{@code action} — the picked action name</li>
 *   <li>{@code summary} — a one-line natural-language summary</li>
 *   <li>{@code data} — structured data the front-end can render</li>
 *   <li>{@code linkTo} — a cross-space link the front-end can navigate to</li>
 * </ul>
 */
public class ProductQueryService {
    private static final Logger log = LoggerFactory.getLogger(ProductQueryService.class);

    private final ModelClient modelClient;
    private final BusinessApprovalService businessApprovalService;
    private final BusinessRunService businessRunService;
    private final TeamBlueprintService teamBlueprintService;
    private final TenantManager tenantManager;
    private final DeadLetterQueue deadLetterQueue;

    // ── Action catalogs per product ─────────────────────────────────

    private static final String CATALOG_PORTAL = """
PORTAL action catalog (pick the most relevant one):

- list_approvals       — list pending/approved/rejected approvals for a workspace
  params: { status?: "PENDING"|"APPROVED"|"REJECTED"|"INFO_REQUESTED", limit?: number }
- list_recent_runs     — recent run history for a workspace
  params: { status?: "COMPLETED"|"FAILED"|"RUNNING"|"NEEDS_APPROVAL", limit?: number }
- list_teams           — list digital employee teams
  params: {  }
- list_pending_tasks   — pending tasks (alias of list_approvals with status=PENDING + list_recent_runs with status=NEEDS_APPROVAL)
  params: { limit?: number }
""";

    private static final String CATALOG_OPS = """
OPS action catalog (pick the most relevant one):

- list_tenants         — list active tenants
  params: {  }
- tenant_stats         — global tenant stats (counts, totals)
  params: {  }
- list_sessions_summary — summarize recent sessions
  params: { limit?: number }
""";

    private static final String CATALOG_NOC = """
NOC action catalog (pick the most relevant one):

- list_dlq             — list dead-letter queue items for a workspace
  params: {  }
- dlq_stats            — DLQ counts by status for a workspace
  params: {  }
""";

    public ProductQueryService(ModelClient modelClient,
                                BusinessApprovalService businessApprovalService,
                                BusinessRunService businessRunService,
                                TeamBlueprintService teamBlueprintService,
                                TenantManager tenantManager,
                                DeadLetterQueue deadLetterQueue) {
        this.modelClient = modelClient;
        this.businessApprovalService = businessApprovalService;
        this.businessRunService = businessRunService;
        this.teamBlueprintService = teamBlueprintService;
        this.tenantManager = tenantManager;
        this.deadLetterQueue = deadLetterQueue;
    }

    /** Dispatch entry point used by IntentRouter after classification. */
    public QueryResult dispatch(String intent, String input, String workspaceId) {
        if (intent == null) return null;
        String wsId = (workspaceId == null || workspaceId.isBlank()) ? "default" : workspaceId;
        return switch (intent.toLowerCase()) {
            case "portal" -> queryPortal(input, wsId);
            case "ops" -> queryOps(input, wsId);
            case "noc" -> queryNoc(input, wsId);
            default -> null;  // "cross" or unknown — no product dispatch
        };
    }

    // ── portal ─────────────────────────────────────────────────────

    private QueryResult queryPortal(String input, String workspaceId) {
        ActionPick pick = pickAction(CATALOG_PORTAL, input);
        if (pick == null) return fallback("portal", "list_approvals", workspaceId);
        try {
            return switch (pick.action) {
                case "list_approvals" -> listApprovals(pick.params, workspaceId);
                case "list_recent_runs" -> listRecentRuns(pick.params, workspaceId);
                case "list_teams" -> listTeams(workspaceId);
                case "list_pending_tasks" -> listPendingTasks(pick.params, workspaceId);
                default -> fallback("portal", "list_approvals", workspaceId);
            };
        } catch (Exception e) {
            log.warn("Portal action {} failed: {}", pick.action, e.getMessage(), e);
            return new QueryResult(pick.action, "（查询失败：" + e.getMessage() + "）", Map.of(), "/portal");
        }
    }

    private QueryResult listApprovals(Map<String, Object> params, String workspaceId) {
        String status = stringParam(params, "status", "PENDING");
        int limit = intParam(params, "limit", 10);
        List<BusinessApprovalRecord> all = businessApprovalService.listApprovals(workspaceId, status);
        if (all.size() > limit) all = all.subList(0, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (BusinessApprovalRecord r : all) {
            items.add(Map.of(
                "approvalId", safe(r.getApprovalId()),
                "title", safe(r.getTitle()),
                "status", safe(r.getStatus()),
                "riskLevel", safe(r.getRiskLevel()),
                "createdAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString()
            ));
        }
        String summary = all.isEmpty()
            ? "工作区 " + workspaceId + " 当前没有 " + status + " 状态的审批。"
            : "工作区 " + workspaceId + " 有 " + all.size() + " 条 " + status + " 审批。";
        return new QueryResult("list_approvals", summary,
            Map.of("items", items, "count", items.size(), "status", status),
            "/portal/approvals");
    }

    private QueryResult listRecentRuns(Map<String, Object> params, String workspaceId) {
        String status = stringParam(params, "status", null);
        int limit = intParam(params, "limit", 10);
        List<BusinessRunRecord> runs = businessRunService.listRuns(
            workspaceId, null, null, status, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (BusinessRunRecord r : runs) {
            items.add(Map.of(
                "runId", safe(r.getRunId()),
                "title", safe(r.getTaskTitle()),
                "status", safe(r.getStatus()),
                "scenario", safe(r.getScenario()),
                "createdAt", r.getCreatedAt() == null ? "" : r.getCreatedAt().toString()
            ));
        }
        String summary = "最近 " + runs.size() + " 条 Run"
            + (status == null ? "" : "（" + status + "）")
            + "。";
        return new QueryResult("list_recent_runs", summary,
            Map.of("items", items, "count", items.size()),
            "/portal/runs");
    }

    private QueryResult listTeams(String workspaceId) {
        var teams = teamBlueprintService.listTeamBlueprints(workspaceId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var t : teams) {
            items.add(Map.of(
                "teamId", safe(t.getTeamId()),
                "name", safe(t.getName()),
                "description", safe(t.getDescription())
            ));
        }
        String summary = "工作区有 " + teams.size() + " 个数字员工团队。";
        return new QueryResult("list_teams", summary,
            Map.of("items", items, "count", items.size()),
            "/portal/teams");
    }

    private QueryResult listPendingTasks(Map<String, Object> params, String workspaceId) {
        int limit = intParam(params, "limit", 5);
        var approvals = listApprovals(Map.of("status", "PENDING", "limit", limit), workspaceId);
        var runs = listRecentRuns(Map.of("status", "NEEDS_APPROVAL", "limit", limit), workspaceId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> approvalItems =
            (List<Map<String, Object>>) approvals.data.getOrDefault("items", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runItems =
            (List<Map<String, Object>>) runs.data.getOrDefault("items", List.of());

        String summary = "待处理任务：审批 " + approvalItems.size()
            + " 条，NEEDS_APPROVAL 的 Run " + runItems.size() + " 条。";
        return new QueryResult("list_pending_tasks", summary,
            Map.of(
                "approvals", approvalItems,
                "runs", runItems,
                "approvalCount", approvalItems.size(),
                "runCount", runItems.size()
            ),
            "/portal");
    }

    // ── ops ────────────────────────────────────────────────────────

    private QueryResult queryOps(String input, String workspaceId) {
        ActionPick pick = pickAction(CATALOG_OPS, input);
        if (pick == null) return fallback("ops", "list_tenants", workspaceId);
        try {
            return switch (pick.action) {
                case "list_tenants" -> listTenants();
                case "tenant_stats" -> tenantStats();
                case "list_sessions_summary" -> listSessionsSummary(pick.params);
                default -> fallback("ops", "list_tenants", workspaceId);
            };
        } catch (Exception e) {
            log.warn("Ops action {} failed: {}", pick.action, e.getMessage(), e);
            return new QueryResult(pick.action, "（查询失败：" + e.getMessage() + "）", Map.of(), "/ops/tenants");
        }
    }

    private QueryResult listTenants() {
        CollectionAsList<TenantContext> tenants = listAll(tenantManager.listActiveTenants());
        List<Map<String, Object>> items = new ArrayList<>();
        for (TenantContext t : tenants) {
            items.add(Map.of(
                "tenantId", safe(t.getTenantId()),
                "state", safe(t.getState() == null ? "" : t.getState().name())
            ));
        }
        String summary = "当前活跃租户 " + tenants.size() + " 个。";
        return new QueryResult("list_tenants", summary,
            Map.of("items", items, "count", items.size()),
            "/ops/tenants");
    }

    private QueryResult tenantStats() {
        var stats = tenantManager.getStats();
        String summary = "租户统计："
            + (stats == null ? "（无数据）" : stats.toString());
        return new QueryResult("tenant_stats", summary,
            stats == null ? Map.of() : Map.of("stats", stats.toString()),
            "/ops/tenants");
    }

    private QueryResult listSessionsSummary(Map<String, Object> params) {
        // Sessions are managed by SessionHandler; for v1 we report the
        // counts from the gateway runtime status which is already in scope.
        // The full session list endpoint can be wired in a follow-up commit
        // if/when we add a SessionService dependency.
        return new QueryResult("list_sessions_summary",
            "会话摘要需在 SessionHandler 上扩展（v1 占位）。",
            Map.of("note", "session-summary-pending"),
            "/ops/sessions");
    }

    // ── noc ────────────────────────────────────────────────────────

    private QueryResult queryNoc(String input, String workspaceId) {
        ActionPick pick = pickAction(CATALOG_NOC, input);
        if (pick == null) return fallback("noc", "list_dlq", workspaceId);
        try {
            return switch (pick.action) {
                case "list_dlq" -> listDlq(workspaceId);
                case "dlq_stats" -> dlqStats(workspaceId);
                default -> fallback("noc", "list_dlq", workspaceId);
            };
        } catch (Exception e) {
            log.warn("NOC action {} failed: {}", pick.action, e.getMessage(), e);
            return new QueryResult(pick.action, "（查询失败：" + e.getMessage() + "）", Map.of(), "/noc/dlq");
        }
    }

    private QueryResult listDlq(String workspaceId) {
        var items = deadLetterQueue.list(workspaceId);
        List<Map<String, Object>> list = new ArrayList<>();
        for (var it : items) {
            list.add(Map.of(
                "itemId", safe(it.itemId()),
                "runId", safe(it.runId()),
                "reason", safe(it.reason()),
                "status", safe(it.status())
            ));
        }
        String summary = "工作区 " + workspaceId + " 死信队列 " + items.size() + " 条。";
        return new QueryResult("list_dlq", summary,
            Map.of("items", list, "count", items.size()),
            "/noc/dlq");
    }

    private QueryResult dlqStats(String workspaceId) {
        var stats = deadLetterQueue.stats(workspaceId);
        return new QueryResult("dlq_stats",
            "DLQ 统计：" + (stats == null ? "（无数据）" : stats.toString()),
            stats == null ? Map.of() : Map.of("stats", stats.toString()),
            "/noc/dlq");
    }

    // ── action picking (LLM) ───────────────────────────────────────

    private ActionPick pickAction(String catalog, String input) {
        String systemPrompt = """
You pick the most relevant action from a product's action catalog based
on the user's free-form question. Reply with a single JSON object and
nothing else, using this exact shape:

{"action": "<action-name>", "params": {<extracted-params>}, "reasoning": "<=15 words"}

If the user's question is too vague to pick a specific action, choose the
most general one (e.g. list_approvals for "show me my stuff" in portal).
""";

        List<ModelMessage> messages = List.of(
            ModelMessage.system(systemPrompt + "\n\n" + catalog),
            ModelMessage.user("用户问题：" + input.trim())
        );

        try {
            ChatCompletionResponse resp = modelClient.chatCompletion(messages, null, false);
            if (resp == null || resp.getContent() == null) return null;
            String content = resp.getContent();
            String json = extractFirstJsonObject(content);
            JSONObject obj = JSON.parseObject(json);
            String action = obj.getString("action");
            if (action == null || action.isBlank()) return null;
            JSONObject params = obj.getJSONObject("params");
            return new ActionPick(action, params == null ? Map.of() : params);
        } catch (Exception e) {
            log.warn("Action pick LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    private static String extractFirstJsonObject(String content) {
        int first = content.indexOf('{');
        if (first < 0) {
            throw new IllegalArgumentException("No JSON object in: " + content);
        }
        int depth = 0;
        for (int i = first; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(first, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("Unterminated JSON object in: " + content);
    }

    // ── helpers ────────────────────────────────────────────────────

    private QueryResult fallback(String product, String action, String workspaceId) {
        return new QueryResult(action,
            "未匹配到精确动作，回退到默认 " + product + " 视图。",
            Map.of("fallback", true, "product", product),
            "/" + product);
    }

    private static String stringParam(Map<String, Object> params, String key, String def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v == null) return def;
        String s = v.toString();
        return s.isBlank() ? def : s;
    }

    private static int intParam(Map<String, Object> params, String key, int def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return def; }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /** Adapter so we can iterate a java.util.Collection without the IDE warning. */
    private static <T> CollectionAsList<T> listAll(java.util.Collection<T> c) {
        return new CollectionAsList<>(c);
    }

    private static final class CollectionAsList<T> extends ArrayList<T> {
        CollectionAsList(java.util.Collection<T> c) { super(c == null ? List.of() : c); }
    }

    private record ActionPick(String action, Map<String, Object> params) {}

    public record QueryResult(
        String action,
        String summary,
        Map<String, Object> data,
        String linkTo
    ) {}
}
