package com.nousresearch.hermes.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.config.Config;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.tenant.core.TenantConfig;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tenant.audit.AuditEvent;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantSkillManager;
import com.nousresearch.hermes.tenant.quota.TenantQuota;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 租户管理 REST API 控制器
 * 
 * 提供租户生命周期管理、配额查询、安全策略配置等接口
 */
public class TenantController implements HttpHandler {
    private static final Logger logger = LoggerFactory.getLogger(TenantController.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final TenantManager tenantManager;
    private final Config config;
    
    public TenantController(TenantManager tenantManager, Config config) {
        this.tenantManager = tenantManager;
        this.config = config;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        
        try {
            // 添加 CORS 头
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
            
            if ("OPTIONS".equals(method)) {
                sendResponse(exchange, 200, "");
                return;
            }
            
            // 路由处理
            if (path.matches("/api/v1/tenants/?")) {
                switch (method) {
                    case "GET" -> listTenants(exchange);
                    case "POST" -> createTenant(exchange);
                    default -> sendError(exchange, 405, "Method not allowed");
                }
            } else if (path.matches("/api/v1/tenants/[^/]+/?")) {
                String tenantId = extractTenantId(path);
                switch (method) {
                    case "GET" -> getTenant(exchange, tenantId);
                    case "DELETE" -> deleteTenant(exchange, tenantId);
                    default -> sendError(exchange, 405, "Method not allowed");
                }
            } else if (path.matches("/api/v1/tenants/[^/]+/suspend")) {
                String tenantId = extractTenantId(path);
                if ("POST".equals(method)) {
                    suspendTenant(exchange, tenantId);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            } else if (path.matches("/api/v1/tenants/[^/]+/resume")) {
                String tenantId = extractTenantId(path);
                if ("POST".equals(method)) {
                    resumeTenant(exchange, tenantId);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            } else if (path.matches("/api/v1/tenants/[^/]+/quota/?")) {
                String tenantId = extractTenantId(path);
                switch (method) {
                    case "GET" -> getQuota(exchange, tenantId);
                    case "PUT" -> updateQuota(exchange, tenantId);
                    default -> sendError(exchange, 405, "Method not allowed");
                }
            } else if (path.matches("/api/v1/tenants/[^/]+/usage/?")) {
                String tenantId = extractTenantId(path);
                if ("GET".equals(method)) {
                    getUsage(exchange, tenantId);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            } else if (path.matches("/api/v1/tenants/[^/]+/security/?")) {
                String tenantId = extractTenantId(path);
                switch (method) {
                    case "GET" -> getSecurityPolicy(exchange, tenantId);
                    case "PUT" -> updateSecurityPolicy(exchange, tenantId);
                    default -> sendError(exchange, 405, "Method not allowed");
                }
            } else if (path.matches("/api/v1/tenants/[^/]+/audit/?")) {
                String tenantId = extractTenantId(path);
                if ("GET".equals(method)) {
                    getAuditLog(exchange, tenantId);
                } else {
                    sendError(exchange, 405, "Method not allowed");
                }
            } else if (path.matches("/api/v1/tenants/[^/]+/skills/?")) {
                String tenantId = extractTenantId(path);
                switch (method) {
                    case "GET" -> listSkills(exchange, tenantId);
                    default -> sendError(exchange, 405, "Method not allowed");
                }
            } else {
                sendError(exchange, 404, "Not found");
            }
            
        } catch (Exception e) {
            logger.error("API error: {}", e.getMessage(), e);
            sendError(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }
    
    // ============ 租户管理 ============
    
    private void listTenants(HttpExchange exchange) throws IOException {
        TenantManager.TenantStats stats = tenantManager.getStats();
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("active_tenants", stats.activeTenants());
        response.put("suspended_tenants", stats.suspendedTenants());
        response.put("total_registered", stats.totalRegistered());
        
        sendJson(exchange, 200, response);
    }
    
    private void createTenant(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> request = mapper.readValue(body, Map.class);
        
        String tenantId = (String) request.get("tenant_id");
        if (tenantId == null || tenantId.isEmpty()) {
            tenantId = "tenant_" + UUID.randomUUID().toString().substring(0, 8);
        }
        
        if (tenantManager.isRegistered(tenantId)) {
            sendError(exchange, 409, "Tenant already exists: " + tenantId);
            return;
        }
        
        // 提取配置
        String name = (String) request.getOrDefault("name", tenantId);
        String description = (String) request.getOrDefault("description", "");
        String createdBy = (String) request.getOrDefault("created_by", "api");
        
        // 配额配置
        TenantQuota quota = TenantQuota.defaults();
        @SuppressWarnings("unchecked")
        Map<String, Object> quotaMap = (Map<String, Object>) request.get("quota");
        if (quotaMap != null) {
            quota = parseQuota(quotaMap);
        }
        
        // 安全策略
        TenantSecurityPolicy securityPolicy = TenantSecurityPolicy.defaults();
        @SuppressWarnings("unchecked")
        Map<String, Object> securityMap = (Map<String, Object>) request.get("security");
        if (securityMap != null) {
            securityPolicy = parseSecurityPolicy(securityMap);
        }
        
        TenantProvisioningRequest provisionRequest = TenantProvisioningRequest.builder(tenantId, createdBy)
            .tenantName(name)
            .description(description)
            .quota(quota)
            .securityPolicy(securityPolicy)
            .build();
        
        TenantContext context = tenantManager.provisionTenant(provisionRequest);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant_id", tenantId);
        response.put("status", "created");
        response.put("created_at", Instant.now().toString());
        response.put("workspace", context.getFileSandbox().getSandboxPath().toString());
        
        sendJson(exchange, 201, response);
        logger.info("Created tenant: {}", tenantId);
    }
    
    private void getTenant(HttpExchange exchange, String tenantId) throws IOException {
        if (!tenantManager.isRegistered(tenantId)) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        TenantContext context = tenantManager.getOrLoadTenant(tenantId);
        if (context == null) {
            sendError(exchange, 404, "Tenant not found or suspended");
            return;
        }
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant_id", tenantId);
        response.put("status", "active");
        response.put("workspace", context.getFileSandbox().getSandboxPath().toString());
        response.put("quota", quotaToMap(context.getQuotaManager().getQuota()));
        response.put("security", securityToMap(context.getSecurityPolicy()));
        
        sendJson(exchange, 200, response);
    }
    
    private void deleteTenant(HttpExchange exchange, String tenantId) throws IOException {
        if (!tenantManager.isRegistered(tenantId)) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        tenantManager.deleteTenant(tenantId);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant_id", tenantId);
        response.put("status", "deleted");
        
        sendJson(exchange, 200, response);
        logger.info("Deleted tenant: {}", tenantId);
    }
    
    private void suspendTenant(HttpExchange exchange, String tenantId) throws IOException {
        if (!tenantManager.isRegistered(tenantId)) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        tenantManager.suspendTenant(tenantId);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant_id", tenantId);
        response.put("status", "suspended");
        
        sendJson(exchange, 200, response);
        logger.info("Suspended tenant: {}", tenantId);
    }
    
    private void resumeTenant(HttpExchange exchange, String tenantId) throws IOException {
        if (!tenantManager.isRegistered(tenantId)) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        tenantManager.resumeTenant(tenantId);
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant_id", tenantId);
        response.put("status", "resumed");
        
        sendJson(exchange, 200, response);
        logger.info("Resumed tenant: {}", tenantId);
    }
    
    // ============ 配额管理 ============
    
    private void getQuota(HttpExchange exchange, String tenantId) throws IOException {
        TenantContext context = tenantManager.getOrLoadTenant(tenantId);
        if (context == null) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        sendJson(exchange, 200, quotaToMap(context.getQuotaManager().getQuota()));
    }
    
    private void updateQuota(HttpExchange exchange, String tenantId) throws IOException {
        TenantContext context = tenantManager.getOrLoadTenant(tenantId);
        if (context == null) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> quotaMap = mapper.readValue(body, Map.class);
        
        TenantQuota quota = parseQuota(quotaMap);
        context.getConfig().set("quota", quota.toMap());
        
        // 保存配置
        context.getConfig().save();
        
        sendJson(exchange, 200, quotaToMap(quota));
        logger.info("Updated quota for tenant: {}", tenantId);
    }
    
    private void getUsage(HttpExchange exchange, String tenantId) throws IOException {
        TenantContext context = tenantManager.getOrLoadTenant(tenantId);
        if (context == null) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant_id", tenantId);
        response.put("storage_bytes", context.getQuotaManager().getStorageUsage());
        response.put("memory_bytes", context.getSessionManager().getActiveSessionCount() * 1024 * 1024); // 估算
        response.put("active_sessions", context.getSessionManager().getActiveSessionCount());
        response.put("active_agents", context.getSessionManager().getActiveSessionCount()); // 简化
        
        sendJson(exchange, 200, response);
    }
    
    // ============ 安全策略 ============
    
    private void getSecurityPolicy(HttpExchange exchange, String tenantId) throws IOException {
        TenantContext context = tenantManager.getOrLoadTenant(tenantId);
        if (context == null) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        sendJson(exchange, 200, securityToMap(context.getSecurityPolicy()));
    }
    
    private void updateSecurityPolicy(HttpExchange exchange, String tenantId) throws IOException {
        TenantContext context = tenantManager.getOrLoadTenant(tenantId);
        if (context == null) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> policyMap = mapper.readValue(body, Map.class);
        
        TenantSecurityPolicy policy = parseSecurityPolicy(policyMap);
        context.setSecurityPolicy(policy);
        
        // 保存配置
        try {
            Path configDir = config.getTenantConfigPath(tenantId).getParent();
            policy.save(configDir);
        } catch (Exception e) {
            logger.warn("Failed to save security policy: {}", e.getMessage());
        }
        
        sendJson(exchange, 200, securityToMap(policy));
        logger.info("Updated security policy for tenant: {}", tenantId);
    }
    
    // ============ 审计日志 ============
    
    private void getAuditLog(HttpExchange exchange, String tenantId) throws IOException {
        TenantContext context = tenantManager.getOrLoadTenant(tenantId);
        if (context == null) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        // 获取查询参数
        String query = exchange.getRequestURI().getQuery();
        final int[] limit = {100};
        final String[] eventType = {null};
        
        if (query != null) {
            for (String param : query.split("&")) {
                String[] parts = param.split("=");
                if (parts.length == 2) {
                    switch (parts[0]) {
                        case "limit" -> limit[0] = Math.min(Integer.parseInt(parts[1]), 1000);
                        case "event_type" -> eventType[0] = parts[1];
                    }
                }
            }
        }
        
        List<Map<String, Object>> events = context.getAuditLogger().getRecentEvents(limit[0]).stream()
            .filter(e -> eventType[0] == null || e.event().name().equalsIgnoreCase(eventType[0]))
            .map(e -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("timestamp", e.timestamp().toString());
                map.put("type", e.event().name());
                map.put("data", e.details());
                return map;
            })
            .collect(Collectors.toList());
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant_id", tenantId);
        response.put("events", events);
        response.put("total", events.size());
        
        sendJson(exchange, 200, response);
    }
    
    // ============ Skills ============
    
    private void listSkills(HttpExchange exchange, String tenantId) throws IOException {
        TenantContext context = tenantManager.getOrLoadTenant(tenantId);
        if (context == null) {
            sendError(exchange, 404, "Tenant not found");
            return;
        }
        
        TenantSkillManager skillManager = context.getSkillManager();
        List<TenantSkillManager.SkillSummary> skills = skillManager.listSkills();
        
        List<Map<String, Object>> skillList = skills.stream()
            .map(s -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("name", s.name());
                map.put("description", s.description());
                map.put("source", s.source());
                map.put("version", String.valueOf(s.version()));
                map.put("read_only", s.readOnly());
                return map;
            })
            .collect(Collectors.toList());
        
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenant_id", tenantId);
        response.put("skills", skillList);
        response.put("total", skillList.size());
        
        sendJson(exchange, 200, response);
    }
    
    // ============ 工具方法 ============
    
    private String extractTenantId(String path) {
        String[] parts = path.split("/");
        return parts[4]; // /api/v1/tenants/{tenantId}/...
    }
    
    private TenantQuota parseQuota(Map<String, Object> map) {
        TenantQuota quota = new TenantQuota();
        if (map.containsKey("max_daily_requests")) {
            quota.setMaxDailyRequests(((Number) map.get("max_daily_requests")).intValue());
        }
        if (map.containsKey("max_daily_tokens")) {
            quota.setMaxDailyTokens(((Number) map.get("max_daily_tokens")).intValue());
        }
        if (map.containsKey("max_concurrent_agents")) {
            quota.setMaxConcurrentAgents(((Number) map.get("max_concurrent_agents")).intValue());
        }
        if (map.containsKey("max_storage_bytes")) {
            quota.setMaxStorageBytes(((Number) map.get("max_storage_bytes")).longValue());
        }
        if (map.containsKey("max_memory_bytes")) {
            quota.setMaxMemoryBytes(((Number) map.get("max_memory_bytes")).longValue());
        }
        if (map.containsKey("max_tool_calls_per_session")) {
            quota.setMaxToolCallsPerSession(((Number) map.get("max_tool_calls_per_session")).intValue());
        }
        return quota;
    }
    
    private Map<String, Object> quotaToMap(TenantQuota quota) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("max_daily_requests", quota.getMaxDailyRequests());
        map.put("max_daily_tokens", quota.getMaxDailyTokens());
        map.put("max_concurrent_agents", quota.getMaxConcurrentAgents());
        map.put("max_concurrent_sessions", quota.getMaxConcurrentSessions());
        map.put("max_storage_bytes", quota.getMaxStorageBytes());
        map.put("max_memory_bytes", quota.getMaxMemoryBytes());
        map.put("requests_per_second", quota.getRequestsPerSecond());
        map.put("requests_per_minute", quota.getRequestsPerMinute());
        map.put("max_tool_calls_per_session", quota.getMaxToolCallsPerSession());
        map.put("max_file_size_bytes", quota.getMaxFileSizeBytes());
        map.put("max_execution_time_seconds", quota.getMaxExecutionTime().getSeconds());
        map.put("allow_code_execution", quota.isAllowCodeExecution());
        return map;
    }
    
    private TenantSecurityPolicy parseSecurityPolicy(Map<String, Object> map) {
        TenantSecurityPolicy policy = new TenantSecurityPolicy();
        if (map.containsKey("allow_code_execution")) {
            policy.setAllowCodeExecution((Boolean) map.get("allow_code_execution"));
        }
        if (map.containsKey("require_sandbox")) {
            policy.setRequireSandbox((Boolean) map.get("require_sandbox"));
        }
        if (map.containsKey("allowed_languages")) {
            @SuppressWarnings("unchecked")
            List<String> langs = (List<String>) map.get("allowed_languages");
            policy.setAllowedLanguages(new HashSet<>(langs));
        }
        if (map.containsKey("allow_network_access")) {
            policy.setAllowNetworkAccess((Boolean) map.get("allow_network_access"));
        }
        if (map.containsKey("allowed_tools")) {
            @SuppressWarnings("unchecked")
            List<String> tools = (List<String>) map.get("allowed_tools");
            policy.setAllowedTools(new HashSet<>(tools));
        }
        if (map.containsKey("denied_tools")) {
            @SuppressWarnings("unchecked")
            List<String> tools = (List<String>) map.get("denied_tools");
            policy.setDeniedTools(new HashSet<>(tools));
        }
        return policy;
    }
    
    private Map<String, Object> securityToMap(TenantSecurityPolicy policy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("allow_code_execution", policy.isAllowCodeExecution());
        map.put("require_sandbox", policy.isRequireSandbox());
        map.put("allowed_languages", policy.getAllowedLanguages());
        map.put("allow_network_access", policy.isAllowNetworkAccess());
        map.put("allowed_hosts", policy.getAllowedHosts());
        map.put("allowed_tools", policy.getAllowedTools());
        map.put("denied_tools", policy.getDeniedTools());
        map.put("allow_file_read", policy.isAllowFileRead());
        map.put("allow_file_write", policy.isAllowFileWrite());
        map.put("denied_paths", policy.getDeniedPaths());
        return map;
    }
    
    private void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    
    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", message);
        error.put("status", status);
        sendJson(exchange, status, error);
    }
    
    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
