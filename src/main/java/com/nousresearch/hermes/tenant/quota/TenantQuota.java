package com.nousresearch.hermes.tenant.quota;

import java.time.Duration;

/**
 * 租户资源配额
 */
public class TenantQuota {
    
    // 请求配额
    private int maxDailyRequests = 10000;
    private long maxDailyTokens = 10_000_000;
    
    // 并发限制
    private int maxConcurrentAgents = 5;
    private int maxConcurrentSessions = 10;
    
    // 资源限制
    private long maxStorageBytes = 1024 * 1024 * 1024; // 1GB
    private long maxMemoryBytes = 512 * 1024 * 1024;   // 512MB
    
    // 速率限制
    private int requestsPerSecond = 10;
    private int requestsPerMinute = 100;
    
    // 工具使用限制
    private int maxToolCallsPerSession = 100;
    private long maxFileSizeBytes = 100 * 1024 * 1024; // 100MB
    
    // 代码执行限制
    private Duration maxExecutionTime = Duration.ofSeconds(30);
    private boolean allowCodeExecution = true;
    
    // Skill 限制
    private int maxPrivateSkills = 50;
    private int maxInstalledSkills = 100;
    
    public static TenantQuota defaults() {
        return new TenantQuota();
    }
    
    // ============ Getters & Setters ============
    
    public int getMaxDailyRequests() { return maxDailyRequests; }
    public void setMaxDailyRequests(int max) { this.maxDailyRequests = max; }
    
    public long getMaxDailyTokens() { return maxDailyTokens; }
    public void setMaxDailyTokens(long max) { this.maxDailyTokens = max; }
    
    public int getMaxConcurrentAgents() { return maxConcurrentAgents; }
    public void setMaxConcurrentAgents(int max) { this.maxConcurrentAgents = max; }
    
    public int getMaxConcurrentSessions() { return maxConcurrentSessions; }
    public void setMaxConcurrentSessions(int max) { this.maxConcurrentSessions = max; }
    
    public long getMaxStorageBytes() { return maxStorageBytes; }
    public void setMaxStorageBytes(long max) { this.maxStorageBytes = max; }
    
    public long getMaxMemoryBytes() { return maxMemoryBytes; }
    public void setMaxMemoryBytes(long max) { this.maxMemoryBytes = max; }
    
    public int getRequestsPerSecond() { return requestsPerSecond; }
    public void setRequestsPerSecond(int rps) { this.requestsPerSecond = rps; }
    
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(int rpm) { this.requestsPerMinute = rpm; }
    
    public int getMaxToolCallsPerSession() { return maxToolCallsPerSession; }
    public void setMaxToolCallsPerSession(int max) { this.maxToolCallsPerSession = max; }
    
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public void setMaxFileSizeBytes(long max) { this.maxFileSizeBytes = max; }
    
    public Duration getMaxExecutionTime() { return maxExecutionTime; }
    public void setMaxExecutionTime(Duration max) { this.maxExecutionTime = max; }
    
    public boolean isAllowCodeExecution() { return allowCodeExecution; }
    public void setAllowCodeExecution(boolean allow) { this.allowCodeExecution = allow; }
    
    public int getMaxPrivateSkills() { return maxPrivateSkills; }
    public void setMaxPrivateSkills(int max) { this.maxPrivateSkills = max; }
    
    public int getMaxInstalledSkills() { return maxInstalledSkills; }
    public void setMaxInstalledSkills(int max) { this.maxInstalledSkills = max; }
    
    /**
     * Convert quota to Map for serialization
     */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("maxDailyRequests", maxDailyRequests);
        map.put("maxDailyTokens", maxDailyTokens);
        map.put("maxConcurrentAgents", maxConcurrentAgents);
        map.put("maxConcurrentSessions", maxConcurrentSessions);
        map.put("maxStorageBytes", maxStorageBytes);
        map.put("maxMemoryBytes", maxMemoryBytes);
        map.put("requestsPerSecond", requestsPerSecond);
        map.put("requestsPerMinute", requestsPerMinute);
        map.put("maxToolCallsPerSession", maxToolCallsPerSession);
        map.put("maxFileSizeBytes", maxFileSizeBytes);
        map.put("maxExecutionTimeSeconds", maxExecutionTime.getSeconds());
        map.put("allowCodeExecution", allowCodeExecution);
        map.put("maxPrivateSkills", maxPrivateSkills);
        map.put("maxInstalledSkills", maxInstalledSkills);
        return map;
    }
}
