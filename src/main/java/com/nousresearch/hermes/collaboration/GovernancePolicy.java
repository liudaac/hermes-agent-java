package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.approval.ToolRisk;
import java.util.*;

public class GovernancePolicy {
    public enum ModelTier { FLASH, STANDARD, PRO }
    
    private long dailyTokenBudget = 100_000;
    private ModelTier defaultModel = ModelTier.STANDARD;
    private ModelTier budgetSafeModel = ModelTier.FLASH;
    private long tokensUsedToday;
    private double minTaskScore = 0.4;
    private int maxConsecutiveFailures = 3;
    private int consecutiveFailures;
    private final Map<ToolRisk, ApprovalSystem.ApprovalMode> approvalMap = new LinkedHashMap<>();
    private String escalationTarget;
    private boolean autoEscalateOnFailure = true;
    private boolean agentPaused;
    private String pauseReason;
    
    public GovernancePolicy() {
        approvalMap.put(ToolRisk.NONE, ApprovalSystem.ApprovalMode.AUTO);
        approvalMap.put(ToolRisk.LOW, ApprovalSystem.ApprovalMode.AUTO);
        approvalMap.put(ToolRisk.MEDIUM, ApprovalSystem.ApprovalMode.PROMPT);
        approvalMap.put(ToolRisk.HIGH, ApprovalSystem.ApprovalMode.REQUIRE);
        approvalMap.put(ToolRisk.CRITICAL, ApprovalSystem.ApprovalMode.DENY);
    }
    
    public GovernancePolicy dailyTokenBudget(long budget) { this.dailyTokenBudget = budget; return this; }
    public GovernancePolicy defaultModel(ModelTier tier) { this.defaultModel = tier; return this; }
    public GovernancePolicy minTaskScore(double score) { this.minTaskScore = score; return this; }
    public GovernancePolicy maxConsecutiveFailures(int n) { this.maxConsecutiveFailures = n; return this; }
    public GovernancePolicy escalationTarget(String id) { this.escalationTarget = id; return this; }
    
    public boolean isOverBudget() { return tokensUsedToday >= dailyTokenBudget; }
    public ModelTier getActiveModel() { return isOverBudget() ? budgetSafeModel : defaultModel; }
    public void recordTokensUsed(long tokens) { this.tokensUsedToday += tokens; }
    public long getTokensUsed() { return tokensUsedToday; }
    public void resetDailyBudget() { this.tokensUsedToday = 0; }
    public long getTokensRemaining() { return Math.max(0, dailyTokenBudget - tokensUsedToday); }
    public long getDailyTokenBudget() { return dailyTokenBudget; }
    
    public void recordSuccess() { consecutiveFailures = 0; if (agentPaused) { agentPaused = false; pauseReason = null; } }
    public boolean recordFailure(String reason) { consecutiveFailures++; if (consecutiveFailures >= maxConsecutiveFailures && autoEscalateOnFailure) { agentPaused = true; pauseReason = reason; } return agentPaused; }
    public boolean isPaused() { return agentPaused; }
    public String getPauseReason() { return pauseReason; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public String getEscalationTarget() { return escalationTarget; }
    
    public ApprovalSystem.ApprovalMode getApprovalMode(ToolRisk risk) {
        return approvalMap.getOrDefault(risk, ApprovalSystem.ApprovalMode.PROMPT);
    }
    
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("daily_budget", dailyTokenBudget);
        m.put("tokens_used_today", tokensUsedToday);
        m.put("tokens_remaining", getTokensRemaining());
        m.put("over_budget", isOverBudget());
        m.put("active_model", getActiveModel().name());
        m.put("consecutive_failures", consecutiveFailures);
        m.put("agent_paused", agentPaused);
        if (pauseReason != null) m.put("pause_reason", pauseReason);
        return m;
    }
}