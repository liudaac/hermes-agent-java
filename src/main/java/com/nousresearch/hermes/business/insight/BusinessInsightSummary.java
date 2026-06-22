package com.nousresearch.hermes.business.insight;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregated Business Portal insights response. */
public class BusinessInsightSummary {
    private String workspaceId;
    private int workspaceCount;
    private int teamCount;
    private int runCount;
    private int failedRunCount;
    private int needsApprovalRunCount;
    private int pendingApprovalCount;
    private int highRiskApprovalCount;
    private double failureRate;
    private List<BusinessInsightRecord> insights = new ArrayList<>();
    private List<Map<String, Object>> nextActions = new ArrayList<>();
    private Instant generatedAt;

    /** 获取WorkspaceId。 */
    public String getWorkspaceId() { return workspaceId; }
    public BusinessInsightSummary setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    /** 获取WorkspaceCount。 */
    public int getWorkspaceCount() { return workspaceCount; }
    public BusinessInsightSummary setWorkspaceCount(int workspaceCount) { this.workspaceCount = workspaceCount; return this; }
    /** 获取TeamCount。 */
    public int getTeamCount() { return teamCount; }
    public BusinessInsightSummary setTeamCount(int teamCount) { this.teamCount = teamCount; return this; }
    /** 获取RunCount。 */
    public int getRunCount() { return runCount; }
    public BusinessInsightSummary setRunCount(int runCount) { this.runCount = runCount; return this; }
    /** 获取FailedRunCount。 */
    public int getFailedRunCount() { return failedRunCount; }
    public BusinessInsightSummary setFailedRunCount(int failedRunCount) { this.failedRunCount = failedRunCount; return this; }
    /** 获取NeedsApprovalRunCount。 */
    public int getNeedsApprovalRunCount() { return needsApprovalRunCount; }
    public BusinessInsightSummary setNeedsApprovalRunCount(int needsApprovalRunCount) { this.needsApprovalRunCount = needsApprovalRunCount; return this; }
    /** 获取PendingApprovalCount。 */
    public int getPendingApprovalCount() { return pendingApprovalCount; }
    public BusinessInsightSummary setPendingApprovalCount(int pendingApprovalCount) { this.pendingApprovalCount = pendingApprovalCount; return this; }
    /** 获取HighRiskApprovalCount。 */
    public int getHighRiskApprovalCount() { return highRiskApprovalCount; }
    public BusinessInsightSummary setHighRiskApprovalCount(int highRiskApprovalCount) { this.highRiskApprovalCount = highRiskApprovalCount; return this; }
    /** 获取FailureRate。 */
    public double getFailureRate() { return failureRate; }
    public BusinessInsightSummary setFailureRate(double failureRate) { this.failureRate = failureRate; return this; }
    /** 获取Insights。 */
    public List<BusinessInsightRecord> getInsights() { return insights; }
    public BusinessInsightSummary setInsights(List<BusinessInsightRecord> insights) { this.insights = insights != null ? insights : new ArrayList<>(); return this; }
    /** 获取NextActions。 */
    public List<Map<String, Object>> getNextActions() { return nextActions; }
    public BusinessInsightSummary setNextActions(List<Map<String, Object>> nextActions) { this.nextActions = nextActions != null ? nextActions : new ArrayList<>(); return this; }
    /** 获取GeneratedAt。 */
    public Instant getGeneratedAt() { return generatedAt; }
    public BusinessInsightSummary setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; return this; }

    public Map<String, Object> metricsMap() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("workspaceCount", workspaceCount);
        metrics.put("teamCount", teamCount);
        metrics.put("runCount", runCount);
        metrics.put("failedRunCount", failedRunCount);
        metrics.put("needsApprovalRunCount", needsApprovalRunCount);
        metrics.put("pendingApprovalCount", pendingApprovalCount);
        metrics.put("highRiskApprovalCount", highRiskApprovalCount);
        metrics.put("failureRate", failureRate);
        return metrics;
    }
}
