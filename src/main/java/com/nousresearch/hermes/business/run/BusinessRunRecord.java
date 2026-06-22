package com.nousresearch.hermes.business.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务运行记录 — 面向 B 端用户的一次完整业务执行实例。
 *
 * <p>与底层 IntentRun 不同，BusinessRunRecord 是人类可读的：
 * 包含任务标题、输入、结果摘要、结论理由、风险判断、下一步建议等字段。
 * 所有字段都设计为可直接展示在 Business Portal 前端。</p>
 * <p>新增编排字段：
 * <ul>
 *   <li>collaborationPattern: 本次运行使用的协作模式</li>
 *   <li>slaName / slaStatus: 绑定的 SLA 及其健康状态</li>
 * </ul>
 */
public class BusinessRunRecord {
    private String runId;
    private String workspaceId;
    private String teamId;
    private String teamVersion;
    private String scenario;
    private String scenarioId;
    private String taskTitle;
    private String taskInput;
    private String resultSummary;
    private String conclusionReason;
    private String systemAction;
    private String riskJudgement;
    private String nextSuggestion;
    private String status = "COMPLETED";
    private String approvalId;
    private String technicalTraceRef;
    private String collaborationPattern;
    private String slaName;
    private String slaStatus;
    private List<BusinessRunStep> steps = new ArrayList<>();
    private long tokensUsed;
    private double estimatedCost;
    private Map<String, Object> metrics = new LinkedHashMap<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public BusinessRunRecord() {
    }

    /** 获取RunId。 */
    public String getRunId() { return runId; }
    public BusinessRunRecord setRunId(String runId) { this.runId = runId; return this; }
    /** 获取WorkspaceId。 */
    public String getWorkspaceId() { return workspaceId; }
    public BusinessRunRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    /** 获取TeamId。 */
    public String getTeamId() { return teamId; }
    public BusinessRunRecord setTeamId(String teamId) { this.teamId = teamId; return this; }
    /** 获取TeamVersion。 */
    public String getTeamVersion() { return teamVersion; }
    public BusinessRunRecord setTeamVersion(String teamVersion) { this.teamVersion = teamVersion; return this; }
    /** 获取Scenario。 */
    public String getScenario() { return scenario; }
    public BusinessRunRecord setScenario(String scenario) { this.scenario = scenario; return this; }
    /** 获取ScenarioId。 */
    public String getScenarioId() { return scenarioId; }
    public BusinessRunRecord setScenarioId(String scenarioId) { this.scenarioId = scenarioId; return this; }
    /** 获取TaskTitle。 */
    public String getTaskTitle() { return taskTitle; }
    public BusinessRunRecord setTaskTitle(String taskTitle) { this.taskTitle = taskTitle; return this; }
    /** 获取TaskInput。 */
    public String getTaskInput() { return taskInput; }
    public BusinessRunRecord setTaskInput(String taskInput) { this.taskInput = taskInput; return this; }
    /** 获取ResultSummary。 */
    public String getResultSummary() { return resultSummary; }
    public BusinessRunRecord setResultSummary(String resultSummary) { this.resultSummary = resultSummary; return this; }
    /** 获取ConclusionReason。 */
    public String getConclusionReason() { return conclusionReason; }
    public BusinessRunRecord setConclusionReason(String conclusionReason) { this.conclusionReason = conclusionReason; return this; }
    /** 获取SystemAction。 */
    public String getSystemAction() { return systemAction; }
    public BusinessRunRecord setSystemAction(String systemAction) { this.systemAction = systemAction; return this; }
    /** 获取RiskJudgement。 */
    public String getRiskJudgement() { return riskJudgement; }
    public BusinessRunRecord setRiskJudgement(String riskJudgement) { this.riskJudgement = riskJudgement; return this; }
    /** 获取NextSuggestion。 */
    public String getNextSuggestion() { return nextSuggestion; }
    public BusinessRunRecord setNextSuggestion(String nextSuggestion) { this.nextSuggestion = nextSuggestion; return this; }
    /** 获取Status。 */
    public String getStatus() { return status; }
    public BusinessRunRecord setStatus(String status) { this.status = status; return this; }
    /** 获取ApprovalId。 */
    public String getApprovalId() { return approvalId; }
    public BusinessRunRecord setApprovalId(String approvalId) { this.approvalId = approvalId; return this; }
    /** 获取TechnicalTraceRef。 */
    public String getTechnicalTraceRef() { return technicalTraceRef; }
    public BusinessRunRecord setTechnicalTraceRef(String technicalTraceRef) { this.technicalTraceRef = technicalTraceRef; return this; }
    /** 获取CollaborationPattern。 */
    public String getCollaborationPattern() { return collaborationPattern; }
    public BusinessRunRecord setCollaborationPattern(String collaborationPattern) { this.collaborationPattern = collaborationPattern; return this; }
    /** 获取SlaName。 */
    public String getSlaName() { return slaName; }
    public BusinessRunRecord setSlaName(String slaName) { this.slaName = slaName; return this; }
    /** 获取SlaStatus。 */
    public String getSlaStatus() { return slaStatus; }
    public BusinessRunRecord setSlaStatus(String slaStatus) { this.slaStatus = slaStatus; return this; }
    /** 获取Steps。 */
    public List<BusinessRunStep> getSteps() { return steps; }
    public BusinessRunRecord setSteps(List<BusinessRunStep> steps) { this.steps = steps != null ? steps : new ArrayList<>(); return this; }
    /** 获取TokensUsed。 */
    public long getTokensUsed() { return tokensUsed; }
    public BusinessRunRecord setTokensUsed(long tokensUsed) { this.tokensUsed = tokensUsed; return this; }
    /** 获取EstimatedCost。 */
    public double getEstimatedCost() { return estimatedCost; }
    public BusinessRunRecord setEstimatedCost(double estimatedCost) { this.estimatedCost = estimatedCost; return this; }
    /** 获取Metrics。 */
    public Map<String, Object> getMetrics() { return metrics; }
    public BusinessRunRecord setMetrics(Map<String, Object> metrics) { this.metrics = metrics != null ? metrics : new LinkedHashMap<>(); return this; }
    /** 获取Metadata。 */
    public Map<String, Object> getMetadata() { return metadata; }
    public BusinessRunRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    /** 获取CreatedAt。 */
    public Instant getCreatedAt() { return createdAt; }
    public BusinessRunRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    /** 获取UpdatedAt。 */
    public Instant getUpdatedAt() { return updatedAt; }
    public BusinessRunRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
}
