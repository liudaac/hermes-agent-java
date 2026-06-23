/**
 * ACP 会话 — 代表一个 MCP 客户端连接的完整生命周期。
 *
 * <p>每个会话绑定到一个 workspace（租户隔离），持有独立的命令执行上下文。
 * 会话负责：
 * <ul>
 *   <li>解析 MCP 协议 JSON 消息</li>
 *   <li>路由命令到 ToolRegistry 或 ScenarioOrchestrator</li>
 *   <li>权限校验（调用前检查角色权限）</li>
 *   <li>审批门控（高风险操作自动挂起等待审批）</li>
 *   <li>运行记录（每次调用生成 BusinessRunRecord）</li>
 *   <li>分叉管理（支持子会话分叉，如并行任务）</li>
 * </ul>
 */
package com.nousresearch.hermes.acp.session;

import com.nousresearch.hermes.acp.security.AcpPermissionChecker;
import com.nousresearch.hermes.acp.protocol.*;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.business.run.BusinessRunStep;
import com.nousresearch.hermes.collaboration.ScenarioOrchestrator;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class AcpSession {
    private static final Logger logger = LoggerFactory.getLogger(AcpSession.class);

    private final String sessionId;
    private final WsContext wsContext;
    private final String workspaceId;
    private final TenantContext tenantContext;

    // 依赖服务
    private final ToolRegistry toolRegistry;
    private final TenantAwareToolDispatcher toolDispatcher;
    private final BusinessApprovalService approvalService;
    private final BusinessRunService runService;
    private final WorkspaceService workspaceService;
    private final ScenarioOrchestrator scenarioOrchestrator;
    private final AcpPermissionChecker permissionChecker;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 会话状态
    private volatile boolean closed = false;
    private final ConcurrentHashMap<String, AcpFork> forks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<AcpResponse>> pendingCommands = new ConcurrentHashMap<>();
    private final Consumer<String> onCloseCallback;

    public AcpSession(String sessionId, WsContext wsContext, String workspaceId,
                      TenantContext tenantContext,
                      ToolRegistry toolRegistry,
                      BusinessApprovalService approvalService,
                      BusinessRunService runService,
                      WorkspaceService workspaceService,
                      ScenarioOrchestrator scenarioOrchestrator,
                      AcpPermissionChecker permissionChecker,
                      Consumer<String> onCloseCallback) {
        this.sessionId = sessionId;
        this.wsContext = wsContext;
        this.workspaceId = workspaceId;
        this.tenantContext = tenantContext;
        this.toolRegistry = toolRegistry;
        this.toolDispatcher = new TenantAwareToolDispatcher(tenantContext, toolRegistry);
        this.approvalService = approvalService;
        this.runService = runService;
        this.workspaceService = workspaceService;
        this.scenarioOrchestrator = scenarioOrchestrator;
        this.permissionChecker = permissionChecker;
        this.onCloseCallback = onCloseCallback;
    }

    /**
     * 接收 WebSocket 消息 — MCP 协议入口。
     */
    public void onMessage(String message) {
        if (closed) return;
        try {
            AcpRequest request = AcpRequest.parse(message);
            handleRequest(request);
        } catch (Exception e) {
            logger.error("Failed to handle ACP message in session {}: {}", sessionId, e.getMessage());
            sendError(null, "Parse error: " + e.getMessage());
        }
    }

    /**
     * 处理单个 MCP 请求 — 权限检查 → 审批门控 → 执行 → 记录。
     */
    private void handleRequest(AcpRequest request) {
        // 1. 权限检查
        if (!permissionChecker.hasPermission(workspaceId, sessionId, request.getToolName())) {
            sendResponse(request.getId(), AcpResponse.error(request.getId(),
                "Permission denied: " + request.getToolName()));
            return;
        }

        // 2. 高风险操作 → 审批门控
        if (permissionChecker.isHighRisk(request.getToolName(), request.getParams())) {
            String approvalId = approvalService.createApproval(
                workspaceId, null,
                "ACP High-Risk Operation: " + request.getToolName(),
                "Session " + sessionId + " requested " + request.getToolName(),
                "Review and approve the operation",
                "Approve", "Reject", "Request more info",
                "HIGH",
                request.getParams(),
                Map.of("sessionId", sessionId, "tool", request.getToolName())
            ).getApprovalId();

            sendResponse(request.getId(), AcpResponse.pending(request.getId(),
                "Operation requires approval: " + approvalId));
            return;
        }

        // 3. 执行命令（真正调用 ToolRegistry）
        CompletableFuture<AcpResponse> future = CompletableFuture.supplyAsync(() ->
            runCommand(request)
        );
        pendingCommands.put(request.getId(), future);

        future.whenComplete((response, ex) -> {
            pendingCommands.remove(request.getId());
            if (ex != null) {
                sendError(request.getId(), ex.getMessage());
            } else {
                sendResponse(request.getId(), response);
            }
        });
    }

    /**
     * 真正执行命令 — 调用 ToolRegistry 或 ScenarioOrchestrator。
     * 这是之前占位字符串的核心替换点。
     */
    private AcpResponse runCommand(AcpRequest request) {
        long startTime = System.currentTimeMillis();
        String toolName = request.getToolName();
        Map<String, Object> params = request.getParams();

        try {
            // 初始化租户上下文（确保工具在正确的租户隔离下运行）
            tenantContext.initCollaboration();

            Object result;
            if ("scenario.execute".equals(toolName)) {
                // 场景执行 — 调用 ScenarioOrchestrator
                String scenarioId = String.valueOf(params.get("scenarioId"));
                String intent = String.valueOf(params.getOrDefault("intent", ""));
                var run = scenarioOrchestrator.execute(intent, scenarioId);
                result = run.toMap();
            } else {
                // 普通工具调用 — 通过 TenantAwareToolDispatcher
                // 正统调用链：权限检查 → 配额 → 审批 → 协商 → 执行 → 审计
                String resultJson = toolDispatcher.dispatch(toolName, params);
                // 尝试解析为对象，失败则保留原始 JSON 字符串
                try {
                    result = MAPPER.readValue(resultJson, Object.class);
                } catch (Exception parseEx) {
                    result = resultJson;
                }

                // 检查 dispatcher 返回的错误对象（被权限/配额/审批拒绝时）
                if (result instanceof Map<?, ?> resultMap && resultMap.containsKey("error")) {
                    recordRun(request, result, "DENIED", String.valueOf(resultMap.get("error")));
                    return AcpResponse.error(request.getId(), String.valueOf(resultMap.get("error")));
                }
            }

            // 记录运行（生成 BusinessRunRecord 用于审计）
            recordRun(request, result, "COMPLETED", null);

            long latency = System.currentTimeMillis() - startTime;
            logger.info("ACP command executed: {} ({}ms) in session {}", toolName, latency, sessionId);

            return AcpResponse.success(request.getId(), result);

        } catch (Exception e) {
            logger.error("ACP command failed: {} in session {}: {}", toolName, sessionId, e.getMessage());
            recordRun(request, null, "FAILED", e.getMessage());
            return AcpResponse.error(request.getId(), e.getMessage());
        }
    }

    /**
     * 记录一次 ACP 调用为 BusinessRunRecord — 用于审计和洞察分析。
     */
    private void recordRun(AcpRequest request, Object result, String status, String error) {
        try {
            List<BusinessRunStep> steps = List.of(
                new BusinessRunStep()
                    .setStepId("acp-" + request.getId())
                    .setTitle("ACP: " + request.getToolName())
                    .setSummary(request.getParams().toString())
                    .setActor("acp-session" + sessionId)
                    .setEvidence(result != null ? result.toString() : error)
                    .setStatus(status)
                    .setTimestamp(Instant.now())
            );

            runService.createRun(
                workspaceId, null,
                "ACP: " + request.getToolName(), null, null,
                request.getParams().toString(),
                result != null ? result.toString() : error,
                null, null, null, null,
                status, null, steps,
                Map.of("latencyMs", System.currentTimeMillis()),
                Map.of("source", "acp", "sessionId", sessionId, "tool", request.getToolName())
            );
        } catch (Exception e) {
            logger.warn("Failed to record ACP run: {}", e.getMessage());
        }
    }

    /**
     * 创建子会话分叉 — 用于并行任务或隔离子流程。
     */
    public AcpFork fork(String forkName) {
        String forkId = sessionId + "/fork/" + forkName;
        AcpFork fork = new AcpFork(forkId, this);
        forks.put(forkId, fork);
        logger.info("ACP fork created: {} in session {}", forkId, sessionId);
        return fork;
    }

    // ---- 消息发送 ----

    private void sendResponse(String commandId, AcpResponse response) {
        if (wsContext.session.isOpen()) {
            wsContext.send(response.toJson());
        }
    }

    private void sendError(String commandId, String error) {
        sendResponse(commandId, AcpResponse.error(commandId != null ? commandId : "unknown", error));
    }

    public void close() {
        closed = true;
        forks.values().forEach(AcpFork::close);
        forks.clear();
        if (onCloseCallback != null) onCloseCallback.accept(sessionId);
        logger.info("ACP session closed: {}", sessionId);
    }

    public Map<String, Object> toSummary() {
        return Map.of(
            "sessionId", sessionId,
            "workspaceId", workspaceId,
            "active", !closed,
            "pendingCommands", pendingCommands.size(),
            "forks", forks.size()
        );
    }

    // Getters
    public String getSessionId() { return sessionId; }
    public String getWorkspaceId() { return workspaceId; }
    public boolean isClosed() { return closed; }
    public boolean isActive() { return !closed; }

    public void broadcastEvent(String eventType, Object data) {
        if (wsContext.session.isOpen()) {
            wsContext.send(com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                "type", eventType,
                "data", data,
                "session_id", sessionId,
                "timestamp", System.currentTimeMillis()
            )));
        }
    }
}
