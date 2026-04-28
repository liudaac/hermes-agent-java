package com.nousresearch.hermes.tenant.audit;

/**
 * 审计事件类型
 */
public enum AuditEvent {
    // 租户生命周期
    TENANT_CREATED,
    TENANT_DESTROYED,
    TENANT_SUSPENDED,
    TENANT_RESUMED,
    
    // Agent 生命周期
    AGENT_CREATED,
    AGENT_DESTROYED,
    
    // 文件操作
    FILE_READ,
    FILE_WRITE,
    FILE_DELETE,
    
    // Skill 操作
    SKILL_CREATED,
    SKILL_UPDATED,
    SKILL_DELETED,
    SKILL_INSTALLED,
    SKILL_UNINSTALLED,
    SKILL_SHARED,
    
    // 代码执行
    CODE_EXECUTED,
    CODE_BLOCKED,
    CODE_TIMEOUT,
    
    // 终端命令
    TERMINAL_COMMAND,
    
    // 工具调用
    TOOL_CALLED,
    
    // Skill 执行
    SKILL_EXECUTED,
    
    // 配额
    QUOTA_EXCEEDED,
    
    // 安全
    SECURITY_VIOLATION,
    AUTHENTICATION_FAILED
}
