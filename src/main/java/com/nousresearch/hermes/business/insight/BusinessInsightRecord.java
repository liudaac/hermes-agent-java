package com.nousresearch.hermes.business.insight;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Business-facing insight generated from runs, approvals and team blueprints. */
public class BusinessInsightRecord {
    private String insightId;
    private String workspaceId;
    private String title;
    private String finding;
    private String possibleCause;
    private String recommendation;
    private String expectedBenefit;
    private String suggestedAction;
    private String severity = "INFO";
    private Map<String, Object> metrics = new LinkedHashMap<>();
    private Instant generatedAt;

    public BusinessInsightRecord() {
    }

    public String getInsightId() { return insightId; }
    public BusinessInsightRecord setInsightId(String insightId) { this.insightId = insightId; return this; }
    public String getWorkspaceId() { return workspaceId; }
    public BusinessInsightRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getTitle() { return title; }
    public BusinessInsightRecord setTitle(String title) { this.title = title; return this; }
    public String getFinding() { return finding; }
    public BusinessInsightRecord setFinding(String finding) { this.finding = finding; return this; }
    public String getPossibleCause() { return possibleCause; }
    public BusinessInsightRecord setPossibleCause(String possibleCause) { this.possibleCause = possibleCause; return this; }
    public String getRecommendation() { return recommendation; }
    public BusinessInsightRecord setRecommendation(String recommendation) { this.recommendation = recommendation; return this; }
    public String getExpectedBenefit() { return expectedBenefit; }
    public BusinessInsightRecord setExpectedBenefit(String expectedBenefit) { this.expectedBenefit = expectedBenefit; return this; }
    public String getSuggestedAction() { return suggestedAction; }
    public BusinessInsightRecord setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; return this; }
    public String getSeverity() { return severity; }
    public BusinessInsightRecord setSeverity(String severity) { this.severity = severity; return this; }
    public Map<String, Object> getMetrics() { return metrics; }
    public BusinessInsightRecord setMetrics(Map<String, Object> metrics) { this.metrics = metrics != null ? metrics : new LinkedHashMap<>(); return this; }
    public Instant getGeneratedAt() { return generatedAt; }
    public BusinessInsightRecord setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; return this; }
}
