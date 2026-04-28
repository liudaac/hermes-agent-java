package com.nousresearch.hermes.tenant.core;

import com.nousresearch.hermes.tenant.audit.AuditEvent;
import com.nousresearch.hermes.tenant.quota.QuotaExceededException;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 租户工具注册表
 * 
 * 为每个租户提供：
 * - 工具权限控制（允许/拒绝列表）
 * - 工具调用配额管理
 * - 参数安全检查
 * - 调用审计
 */
public class TenantToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(TenantToolRegistry.class);
    
    // 敏感参数模式（防止信息泄露）
    private static final Set<String> SENSITIVE_PARAM_PATTERNS = Set.of(
        "password", "secret", "token", "key", "credential", "api_key", "private_key"
    );
    
    // 危险命令模式（需要额外检查）
    private static final Set<String> DANGEROUS_PATTERNS = Set.of(
        "rm -rf", "rm -fr", "> /dev/null", "mkfs", "dd if", 
        "curl.*|.*sh", "wget.*|.*sh", "eval", "exec"
    );
    
    private final TenantContext context;
    private final TenantSecurityPolicy securityPolicy;
    private final AtomicInteger sessionToolCalls = new AtomicInteger(0);
    private final Map<String, AtomicInteger> toolCallCounts = new ConcurrentHashMap<>();
    
    public TenantToolRegistry(TenantContext context) {
        this.context = context;
        this.securityPolicy = context.getSecurityPolicy();
    }
    
    /**
     * 检查工具调用权限（完整权限检查链）
     */
    public PermissionCheckResult checkPermission(String toolName, Map<String, Object> args) {
        // 1. 检查安全策略 - 拒绝列表优先
        if (securityPolicy.getDeniedTools().contains(toolName)) {
            logger.warn("Tenant {}: Tool {} is in denied list", context.getTenantId(), toolName);
            return PermissionCheckResult.denied("Tool is explicitly denied for this tenant");
        }
        
        // 2. 检查安全策略 - 允许列表
        Set<String> allowedTools = securityPolicy.getAllowedTools();
        if (!allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
            logger.warn("Tenant {}: Tool {} not in allowed list", context.getTenantId(), toolName);
            return PermissionCheckResult.denied("Tool is not in the allowed list for this tenant");
        }
        
        // 3. 检查会话工具调用配额
        int maxCallsPerSession = context.getQuotaManager().getQuota().getMaxToolCallsPerSession();
        if (sessionToolCalls.get() >= maxCallsPerSession) {
            logger.warn("Tenant {}: Tool call quota exceeded: {}/{}", 
                context.getTenantId(), sessionToolCalls.get(), maxCallsPerSession);
            return PermissionCheckResult.denied("Tool call quota exceeded for this session");
        }
        
        // 4. 参数安全检查
        ParamCheckResult paramCheck = checkArguments(toolName, args);
        if (!paramCheck.isSafe()) {
            logger.warn("Tenant {}: Tool {} parameter check failed: {}", 
                context.getTenantId(), toolName, paramCheck.getReason());
            return PermissionCheckResult.denied("Parameter check failed: " + paramCheck.getReason());
        }
        
        // 5. 敏感数据检查
        if (containsSensitiveData(args)) {
            logger.info("Tenant {}: Tool {} contains sensitive data, logging restricted", 
                context.getTenantId(), toolName);
        }
        
        return PermissionCheckResult.allowed();
    }
    
    /**
     * 记录工具调用
     */
    public void recordToolCall(String toolName, Map<String, Object> args, String result) {
        // 增加计数
        sessionToolCalls.incrementAndGet();
        toolCallCounts.computeIfAbsent(toolName, k -> new AtomicInteger(0)).incrementAndGet();
        
        // 审计日志（脱敏）
        Map<String, Object> auditData = Map.of(
            "tenantId", context.getTenantId(),
            "tool", toolName,
            "args", maskSensitiveArgs(args),
            "success", !result.contains("\"error\""),
            "sessionCalls", sessionToolCalls.get()
        );
        
        context.getAuditLogger().log(AuditEvent.TOOL_CALLED, auditData);
        
        logger.debug("Tenant {}: Tool {} called (session: {})", 
            context.getTenantId(), toolName, sessionToolCalls.get());
    }
    
    /**
     * 重置会话计数（新会话开始时调用）
     */
    public void resetSessionCount() {
        sessionToolCalls.set(0);
        toolCallCounts.clear();
        logger.debug("Tenant {}: Tool call counters reset", context.getTenantId());
    }
    
    /**
     * 获取当前会话工具调用统计
     */
    public ToolCallStats getStats() {
        return new ToolCallStats(
            sessionToolCalls.get(),
            context.getQuotaManager().getQuota().getMaxToolCallsPerSession(),
            Map.copyOf(toolCallCounts)
        );
    }
    
    // ============ 私有方法 ============
    
    private ParamCheckResult checkArguments(String toolName, Map<String, Object> args) {
        // 检查危险命令模式（主要针对 terminal/bash 类工具）
        if (args.containsKey("command") || args.containsKey("code")) {
            String code = (String) args.getOrDefault("command", args.get("code"));
            if (code != null) {
                String lowerCode = code.toLowerCase();
                for (String pattern : DANGEROUS_PATTERNS) {
                    if (lowerCode.contains(pattern.toLowerCase().replace(".*", ""))) {
                        // 警告但不阻止（由审批系统决定）
                        logger.warn("Tenant {}: Potentially dangerous pattern detected: {}", 
                            context.getTenantId(), pattern);
                    }
                }
            }
        }
        
        // 检查路径参数（文件操作工具）
        if (args.containsKey("path") || args.containsKey("file_path")) {
            String path = (String) args.getOrDefault("path", args.get("file_path"));
            if (path != null) {
                // 检查是否尝试访问沙箱外
                if (path.startsWith("/") && !path.startsWith(context.getFileSandbox().getSandboxPath().toString())) {
                    // 路径规范化检查
                    if (path.contains("..") || path.contains("~")) {
                        return ParamCheckResult.failed("Path traversal attempt detected");
                    }
                }
            }
        }
        
        return ParamCheckResult.passed();
    }
    
    private boolean containsSensitiveData(Map<String, Object> args) {
        for (String key : args.keySet()) {
            String lowerKey = key.toLowerCase();
            for (String pattern : SENSITIVE_PARAM_PATTERNS) {
                if (lowerKey.contains(pattern)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private Map<String, Object> maskSensitiveArgs(Map<String, Object> args) {
        Map<String, Object> masked = new java.util.HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            String lowerKey = key.toLowerCase();
            boolean isSensitive = SENSITIVE_PARAM_PATTERNS.stream()
                .anyMatch(lowerKey::contains);
            
            if (isSensitive && value instanceof String) {
                String str = (String) value;
                masked.put(key, str.substring(0, Math.min(3, str.length())) + "***");
            } else {
                masked.put(key, value);
            }
        }
        return masked;
    }
    
    // ============ 记录类 ============
    
    public static class PermissionCheckResult {
        private final boolean allowed;
        private final String reason;
        
        private PermissionCheckResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
        
        public static PermissionCheckResult allowed() {
            return new PermissionCheckResult(true, null);
        }
        
        public static PermissionCheckResult denied(String reason) {
            return new PermissionCheckResult(false, reason);
        }
        
        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }
    
    private static class ParamCheckResult {
        private final boolean safe;
        private final String reason;
        
        private ParamCheckResult(boolean safe, String reason) {
            this.safe = safe;
            this.reason = reason;
        }
        
        static ParamCheckResult passed() {
            return new ParamCheckResult(true, null);
        }
        
        static ParamCheckResult failed(String reason) {
            return new ParamCheckResult(false, reason);
        }
        
        boolean isSafe() { return safe; }
        String getReason() { return reason; }
    }
    
    public record ToolCallStats(
        int currentCalls,
        int maxCalls,
        Map<String, AtomicInteger> toolCounts
    ) {
        public double getUsagePercent() {
            return maxCalls > 0 ? (double) currentCalls / maxCalls * 100 : 0;
        }
        
        public boolean isQuotaExceeded() {
            return currentCalls >= maxCalls;
        }
    }
}
