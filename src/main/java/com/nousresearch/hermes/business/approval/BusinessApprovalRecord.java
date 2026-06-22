package com.nousresearch.hermes.business.approval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business-facing approval card for Business Portal and mobile approvals. */
public class BusinessApprovalRecord {
    private String approvalId;
    private String workspaceId;
    private String teamId;
    private String runId;
    private String scenarioId;
    private String title;
    private String summary;
    private String reasonRequired;
    private String approveEffect;
    private String rejectEffect;
    private String recommendation;
    private String riskLevel = "LOW";
    private String status = "PENDING";
    private Map<String, Object> evidence = new LinkedHashMap<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;
    private String resolvedBy;
    private String resolutionReason;
    private String requestedInfo;
    private List<Map<String, Object>> timeline = new java.util.ArrayList<>();

    public BusinessApprovalRecord() {
    }

    /** 获取ApprovalId。 */
    public String getApprovalId() { return approvalId; }
    public BusinessApprovalRecord setApprovalId(String approvalId) { this.approvalId = approvalId; return this; }
    /** 获取WorkspaceId。 */
    public String getWorkspaceId() { return workspaceId; }
    public BusinessApprovalRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    /** 获取TeamId。 */
    public String getTeamId() { return teamId; }
    public BusinessApprovalRecord setTeamId(String teamId) { this.teamId = teamId; return this; }
    /** 获取RunId。 */
    public String getRunId() { return runId; }
    public BusinessApprovalRecord setRunId(String runId) { this.runId = runId; return this; }
    /** 获取ScenarioId。 */
    public String getScenarioId() { return scenarioId; }
    public BusinessApprovalRecord setScenarioId(String scenarioId) { this.scenarioId = scenarioId; return this; }
    /** 获取Title。 */
    public String getTitle() { return title; }
    public BusinessApprovalRecord setTitle(String title) { this.title = title; return this; }
    /** 获取Summary。 */
    public String getSummary() { return summary; }
    public BusinessApprovalRecord setSummary(String summary) { this.summary = summary; return this; }
    /** 获取ReasonRequired。 */
    public String getReasonRequired() { return reasonRequired; }
    public BusinessApprovalRecord setReasonRequired(String reasonRequired) { this.reasonRequired = reasonRequired; return this; }
    /** 获取ApproveEffect。 */
    public String getApproveEffect() { return approveEffect; }
    public BusinessApprovalRecord setApproveEffect(String approveEffect) { this.approveEffect = approveEffect; return this; }
    /** 获取RejectEffect。 */
    public String getRejectEffect() { return rejectEffect; }
    public BusinessApprovalRecord setRejectEffect(String rejectEffect) { this.rejectEffect = rejectEffect; return this; }
    /** 获取Recommendation。 */
    public String getRecommendation() { return recommendation; }
    public BusinessApprovalRecord setRecommendation(String recommendation) { this.recommendation = recommendation; return this; }
    /** 获取RiskLevel。 */
    public String getRiskLevel() { return riskLevel; }
    public BusinessApprovalRecord setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; return this; }
    /** 获取Status。 */
    public String getStatus() { return status; }
    public BusinessApprovalRecord setStatus(String status) { this.status = status; return this; }
    /** 获取Evidence。 */
    public Map<String, Object> getEvidence() { return evidence; }
    public BusinessApprovalRecord setEvidence(Map<String, Object> evidence) { this.evidence = evidence != null ? evidence : new LinkedHashMap<>(); return this; }
    /** 获取Metadata。 */
    public Map<String, Object> getMetadata() { return metadata; }
    public BusinessApprovalRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    /** 获取CreatedAt。 */
    public Instant getCreatedAt() { return createdAt; }
    public BusinessApprovalRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    /** 获取UpdatedAt。 */
    public Instant getUpdatedAt() { return updatedAt; }
    public BusinessApprovalRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
    /** 获取ResolvedAt。 */
    public Instant getResolvedAt() { return resolvedAt; }
    public BusinessApprovalRecord setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; return this; }
    /** 获取ResolvedBy。 */
    public String getResolvedBy() { return resolvedBy; }
    public BusinessApprovalRecord setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; return this; }
    /** 获取ResolutionReason。 */
    public String getResolutionReason() { return resolutionReason; }
    public BusinessApprovalRecord setResolutionReason(String resolutionReason) { this.resolutionReason = resolutionReason; return this; }
    /** 获取RequestedInfo。 */
    public String getRequestedInfo() { return requestedInfo; }
    public BusinessApprovalRecord setRequestedInfo(String requestedInfo) { this.requestedInfo = requestedInfo; return this; }
    /** 获取Timeline。 */
    public List<Map<String, Object>> getTimeline() { return timeline; }
    public BusinessApprovalRecord setTimeline(List<Map<String, Object>> timeline) { this.timeline = timeline != null ? timeline : new java.util.ArrayList<>(); return this; }
    public BusinessApprovalRecord addTimelineEntry(String action, String actor, String detail, Map<String, Object> data) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("action", action);
        entry.put("actor", actor);
        entry.put("detail", detail);
        entry.put("timestamp", Instant.now().toString());
        if (data != null && !data.isEmpty()) {
            entry.put("data", data);
        }
        this.timeline.add(entry);
        return this;
    }
}
