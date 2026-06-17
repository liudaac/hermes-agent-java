package com.nousresearch.hermes.evolution;

import com.nousresearch.hermes.approval.ApprovalRequest;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.business.approval.BusinessApprovalAdapter;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.collaboration.ContextPressureReport;
import com.nousresearch.hermes.collaboration.DelegatedTask;
import com.nousresearch.hermes.collaboration.DelegatedTaskEnvelope;
import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter between Business Portal evolution proposals and Hermes foundation
 * evolution / approval / delegated-task boundaries.
 *
 * <p>The adapter does not apply runtime mutations. It projects proposals into
 * foundation learning records, approval cards and advisory delegated tasks so
 * existing foundation components remain the source of truth.</p>
 */
public class EvolutionProposalAdapter {
    private final WorkspaceService workspaceService;
    private final TenantManager tenantManager;
    private final BusinessApprovalAdapter approvalAdapter;

    public EvolutionProposalAdapter(WorkspaceService workspaceService, TenantManager tenantManager) {
        this(workspaceService, tenantManager, new BusinessApprovalAdapter());
    }

    public EvolutionProposalAdapter(WorkspaceService workspaceService, TenantManager tenantManager,
                                    BusinessApprovalAdapter approvalAdapter) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.tenantManager = Objects.requireNonNull(tenantManager, "tenantManager");
        this.approvalAdapter = Objects.requireNonNull(approvalAdapter, "approvalAdapter");
    }

    /** Convert a business proposal into a foundation FailureCase. Does not record it. */
    public FailureCase toFailureCase(EvolutionProposalRecord proposal) {
        Objects.requireNonNull(proposal, "proposal");
        FailureCase.Builder builder = new FailureCase.Builder(
            agentId(proposal),
            nonBlank(proposal.getFinding(), proposal.getTitle()),
            nonBlank(proposal.getFinding(), "Business proposal identified an improvement opportunity")
        )
            .id(proposal.getProposalId())
            .expectedOutcome(nonBlank(proposal.getExpectedBenefit(), "Improve business outcome"))
            .rootCause(rootCause(proposal))
            .severity(severity(proposal))
            .diagnosis(nonBlank(proposal.getFinding(), "Business review finding"))
            .lesson(nonBlank(proposal.getProposedChange(), proposal.getTitle()))
            .correctiveAction(nonBlank(proposal.getProposedChange(), "Review proposed change"))
            .contextHint("scenarioId", nonBlank(proposal.getScenarioId(), ""))
            .contextHint("teamId", nonBlank(proposal.getTeamId(), ""))
            .occurredAt(proposal.getCreatedAt() != null ? proposal.getCreatedAt() : Instant.now());
        return builder.build();
    }

    /** Record proposal learning into SelfEvolutionEngine. */
    public FailureCase recordFailureLearning(EvolutionProposalRecord proposal) {
        TenantContext tenant = requireTenant(proposal.getWorkspaceId());
        return tenant.getEvolutionEngine().recordFailure(toFailureCase(proposal));
    }

    /** Build a foundation approval request for applying/reviewing a proposal. Does not submit it. */
    public ApprovalRequest toApprovalRequest(EvolutionProposalRecord proposal) {
        Objects.requireNonNull(proposal, "proposal");
        return new ApprovalRequest(
            ApprovalSystem.ApprovalType.CODE_EXECUTION,
            "evolution-proposal:" + proposal.getProposalId(),
            approvalDetails(proposal),
            isHighRisk(proposal)
        );
    }

    /** Project a proposal approval request into a Business Portal approval card. */
    public BusinessApprovalRecord toBusinessApprovalCard(EvolutionProposalRecord proposal) {
        BusinessApprovalRecord card = approvalAdapter.fromApprovalRequest(
            proposal.getWorkspaceId(),
            firstNonBlank(proposal.getTargetTeamId(), proposal.getTeamId()),
            toApprovalRequest(proposal)
        );
        Map<String, Object> metadata = new LinkedHashMap<>(card.getMetadata());
        metadata.put("source", "foundation:evolution-proposal-approval");
        metadata.put("proposalId", proposal.getProposalId());
        metadata.put("scenarioId", proposal.getScenarioId());
        metadata.put("sourceInsightId", proposal.getSourceInsightId());
        Map<String, Object> evidence = new LinkedHashMap<>(card.getEvidence());
        evidence.put("proposalTitle", proposal.getTitle());
        evidence.put("finding", proposal.getFinding());
        evidence.put("proposedChange", proposal.getProposedChange());
        evidence.put("expectedBenefit", proposal.getExpectedBenefit());
        return card
            .setTitle("Evolution proposal approval: " + nonBlank(proposal.getTitle(), proposal.getProposalId()))
            .setSummary("Review whether this business evolution proposal should proceed: " + nonBlank(proposal.getProposedChange(), ""))
            .setReasonRequired("Evolution proposals can change team behavior, prompts or operating manuals.")
            .setApproveEffect("Proposal may proceed to governed apply/versioning flow.")
            .setRejectEffect("Proposal remains rejected and no foundation mutation is applied.")
            .setRecommendation(isHighRisk(proposal) ? "Review with operator approval before applying." : "Approve if evidence supports the expected benefit.")
            .setRiskLevel(isHighRisk(proposal) ? "HIGH" : card.getRiskLevel())
            .setMetadata(metadata)
            .setEvidence(evidence);
    }

    /** Create an advisory delegated task for governed proposal application. */
    public DelegatedTask createDelegatedReviewTask(EvolutionProposalRecord proposal) {
        TenantContext tenant = requireTenant(proposal.getWorkspaceId());
        DelegatedTaskEnvelope envelope = toDelegatedTaskEnvelope(proposal);
        return tenant.getDelegatedTaskStore().createPending(envelope);
    }

    /** Build an inert delegated-task envelope. Does not store it. */
    public DelegatedTaskEnvelope toDelegatedTaskEnvelope(EvolutionProposalRecord proposal) {
        Objects.requireNonNull(proposal, "proposal");
        Map<String, Object> context = new LinkedHashMap<>(ContextPressureReport.none().toMap());
        context.put("source", "business-evolution-proposal");
        context.put("proposal_id", proposal.getProposalId());
        context.put("scenario_id", proposal.getScenarioId());
        context.put("team_id", proposal.getTeamId());
        context.put("risk", isHighRisk(proposal) ? "HIGH" : "MEDIUM");
        return new DelegatedTaskEnvelope(
            "Review/apply evolution proposal " + proposal.getProposalId() + ": " + nonBlank(proposal.getProposedChange(), proposal.getTitle()),
            "evolution-proposal:" + proposal.getProposalId(),
            firstNonBlank(proposal.getTargetTeamId(), proposal.getTeamId()),
            "evolution-review",
            "Governed review required before applying proposal to team blueprint/runtime policy",
            context,
            Instant.now()
        );
    }

    private TenantContext requireTenant(String workspaceId) {
        WorkspaceRecord workspace = workspaceService.requireWorkspace(workspaceId);
        TenantContext tenant = tenantManager.getTenant(workspace.getTenantId());
        if (tenant == null) throw new IllegalStateException("Workspace tenant is not available: " + workspace.getTenantId());
        return tenant;
    }

    private String approvalDetails(EvolutionProposalRecord proposal) {
        return "Title: " + nonBlank(proposal.getTitle(), proposal.getProposalId())
            + "\nFinding: " + nonBlank(proposal.getFinding(), "")
            + "\nProposed change: " + nonBlank(proposal.getProposedChange(), "")
            + "\nExpected benefit: " + nonBlank(proposal.getExpectedBenefit(), "")
            + "\nTarget team: " + nonBlank(firstNonBlank(proposal.getTargetTeamId(), proposal.getTeamId()), "");
    }

    private boolean isHighRisk(EvolutionProposalRecord proposal) {
        String text = (nonBlank(proposal.getProposedChange(), "") + " " + nonBlank(proposal.getFinding(), "") + " " + proposal.getEvidence()).toLowerCase();
        return text.contains("tool") || text.contains("approval") || text.contains("permission")
            || text.contains("runtime") || text.contains("高风险") || text.contains("审批") || text.contains("权限");
    }

    private FailureCase.RootCause rootCause(EvolutionProposalRecord proposal) {
        Object raw = firstValue(proposal, "rootCause", "root_cause");
        if (raw != null) {
            try { return FailureCase.RootCause.valueOf(String.valueOf(raw).trim().toUpperCase()); }
            catch (Exception ignored) {}
        }
        String text = (nonBlank(proposal.getFinding(), "") + " " + nonBlank(proposal.getProposedChange(), "")).toLowerCase();
        if (text.contains("context") || text.contains("上下文")) return FailureCase.RootCause.INSUFFICIENT_CONTEXT;
        if (text.contains("tool") || text.contains("工具")) return FailureCase.RootCause.WRONG_TOOL;
        if (text.contains("permission") || text.contains("权限")) return FailureCase.RootCause.PERMISSION_DENIED;
        if (text.contains("timeout") || text.contains("超时")) return FailureCase.RootCause.TIMEOUT;
        if (text.contains("partial") || text.contains("部分")) return FailureCase.RootCause.PARTIAL_COMPLETION;
        return FailureCase.RootCause.UNKNOWN;
    }

    private FailureCase.Severity severity(EvolutionProposalRecord proposal) {
        Object raw = firstValue(proposal, "severity", "risk", "riskLevel", "risk_level");
        if (raw != null) {
            try { return FailureCase.Severity.valueOf(String.valueOf(raw).trim().toUpperCase()); }
            catch (Exception ignored) {}
        }
        return isHighRisk(proposal) ? FailureCase.Severity.HIGH : FailureCase.Severity.MEDIUM;
    }

    private Object firstValue(EvolutionProposalRecord proposal, String... keys) {
        for (String key : keys) {
            if (proposal.getEvidence() != null && proposal.getEvidence().containsKey(key)) return proposal.getEvidence().get(key);
            if (proposal.getMetadata() != null && proposal.getMetadata().containsKey(key)) return proposal.getMetadata().get(key);
        }
        return null;
    }

    private String agentId(EvolutionProposalRecord proposal) {
        return firstNonBlank(proposal.getTeamId(), proposal.getTargetTeamId(), proposal.getSourceInsightId(), "business-evolution");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
