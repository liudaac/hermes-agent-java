package com.nousresearch.hermes.business.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business-facing run record with story-style trace. */
public class BusinessRunRecord {
    private String runId;
    private String workspaceId;
    private String teamId;
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
    private String technicalTraceRef;
    private List<BusinessRunStep> steps = new ArrayList<>();
    private Map<String, Object> metrics = new LinkedHashMap<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public BusinessRunRecord() {
    }

    public String getRunId() { return runId; }
    public BusinessRunRecord setRunId(String runId) { this.runId = runId; return this; }
    public String getWorkspaceId() { return workspaceId; }
    public BusinessRunRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getTeamId() { return teamId; }
    public BusinessRunRecord setTeamId(String teamId) { this.teamId = teamId; return this; }
    public String getScenario() { return scenario; }
    public BusinessRunRecord setScenario(String scenario) { this.scenario = scenario; return this; }
    public String getScenarioId() { return scenarioId; }
    public BusinessRunRecord setScenarioId(String scenarioId) { this.scenarioId = scenarioId; return this; }
    public String getTaskTitle() { return taskTitle; }
    public BusinessRunRecord setTaskTitle(String taskTitle) { this.taskTitle = taskTitle; return this; }
    public String getTaskInput() { return taskInput; }
    public BusinessRunRecord setTaskInput(String taskInput) { this.taskInput = taskInput; return this; }
    public String getResultSummary() { return resultSummary; }
    public BusinessRunRecord setResultSummary(String resultSummary) { this.resultSummary = resultSummary; return this; }
    public String getConclusionReason() { return conclusionReason; }
    public BusinessRunRecord setConclusionReason(String conclusionReason) { this.conclusionReason = conclusionReason; return this; }
    public String getSystemAction() { return systemAction; }
    public BusinessRunRecord setSystemAction(String systemAction) { this.systemAction = systemAction; return this; }
    public String getRiskJudgement() { return riskJudgement; }
    public BusinessRunRecord setRiskJudgement(String riskJudgement) { this.riskJudgement = riskJudgement; return this; }
    public String getNextSuggestion() { return nextSuggestion; }
    public BusinessRunRecord setNextSuggestion(String nextSuggestion) { this.nextSuggestion = nextSuggestion; return this; }
    public String getStatus() { return status; }
    public BusinessRunRecord setStatus(String status) { this.status = status; return this; }
    public String getTechnicalTraceRef() { return technicalTraceRef; }
    public BusinessRunRecord setTechnicalTraceRef(String technicalTraceRef) { this.technicalTraceRef = technicalTraceRef; return this; }
    public List<BusinessRunStep> getSteps() { return steps; }
    public BusinessRunRecord setSteps(List<BusinessRunStep> steps) { this.steps = steps != null ? steps : new ArrayList<>(); return this; }
    public Map<String, Object> getMetrics() { return metrics; }
    public BusinessRunRecord setMetrics(Map<String, Object> metrics) { this.metrics = metrics != null ? metrics : new LinkedHashMap<>(); return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public BusinessRunRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    public Instant getCreatedAt() { return createdAt; }
    public BusinessRunRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    public Instant getUpdatedAt() { return updatedAt; }
    public BusinessRunRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
}
