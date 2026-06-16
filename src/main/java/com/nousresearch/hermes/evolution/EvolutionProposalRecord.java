package com.nousresearch.hermes.evolution;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Business evolution proposal generated from insight or human review. */
public class EvolutionProposalRecord {
    private String workspaceId;
    private String proposalId;
    private String scenarioId;
    private String teamId;
    private String sourceInsightId;
    private String title;
    private String finding;
    private String proposedChange;
    private String expectedBenefit;
    private String status = "DRAFT";
    private String targetTeamId;
    private Integer targetDraftVersion;
    private String approvalId;
    private Map<String, Object> evidence = new LinkedHashMap<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;
    private Instant appliedAt;

    public String getWorkspaceId() { return workspaceId; }
    public EvolutionProposalRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getProposalId() { return proposalId; }
    public EvolutionProposalRecord setProposalId(String proposalId) { this.proposalId = proposalId; return this; }
    public String getScenarioId() { return scenarioId; }
    public EvolutionProposalRecord setScenarioId(String scenarioId) { this.scenarioId = scenarioId; return this; }
    public String getTeamId() { return teamId; }
    public EvolutionProposalRecord setTeamId(String teamId) { this.teamId = teamId; return this; }
    public String getSourceInsightId() { return sourceInsightId; }
    public EvolutionProposalRecord setSourceInsightId(String sourceInsightId) { this.sourceInsightId = sourceInsightId; return this; }
    public String getTitle() { return title; }
    public EvolutionProposalRecord setTitle(String title) { this.title = title; return this; }
    public String getFinding() { return finding; }
    public EvolutionProposalRecord setFinding(String finding) { this.finding = finding; return this; }
    public String getProposedChange() { return proposedChange; }
    public EvolutionProposalRecord setProposedChange(String proposedChange) { this.proposedChange = proposedChange; return this; }
    public String getExpectedBenefit() { return expectedBenefit; }
    public EvolutionProposalRecord setExpectedBenefit(String expectedBenefit) { this.expectedBenefit = expectedBenefit; return this; }
    public String getStatus() { return status; }
    public EvolutionProposalRecord setStatus(String status) { this.status = status; return this; }
    public String getTargetTeamId() { return targetTeamId; }
    public EvolutionProposalRecord setTargetTeamId(String targetTeamId) { this.targetTeamId = targetTeamId; return this; }
    public Integer getTargetDraftVersion() { return targetDraftVersion; }
    public EvolutionProposalRecord setTargetDraftVersion(Integer targetDraftVersion) { this.targetDraftVersion = targetDraftVersion; return this; }
    public String getApprovalId() { return approvalId; }
    public EvolutionProposalRecord setApprovalId(String approvalId) { this.approvalId = approvalId; return this; }
    public Map<String, Object> getEvidence() { return evidence; }
    public EvolutionProposalRecord setEvidence(Map<String, Object> evidence) { this.evidence = evidence != null ? evidence : new LinkedHashMap<>(); return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public EvolutionProposalRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    public Instant getCreatedAt() { return createdAt; }
    public EvolutionProposalRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    public Instant getUpdatedAt() { return updatedAt; }
    public EvolutionProposalRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
    public Instant getAppliedAt() { return appliedAt; }
    public EvolutionProposalRecord setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; return this; }
}
