package com.nousresearch.hermes.business.safetyvalve;

import com.nousresearch.hermes.approval.ApprovalRequest;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.blueprint.TeamBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintVersion;
import com.nousresearch.hermes.business.approval.BusinessApprovalAdapter;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.collaboration.ContextPressureReport;
import com.nousresearch.hermes.collaboration.DelegatedTaskEnvelope;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only adapter that turns Business Portal safety-valve intents (replay, canary,
 * rollback) into foundation-grounded review artifacts.
 *
 * <p>Implementation follows the contract in
 * {@code docs/BUSINESS_PORTAL_FOUNDATION_SAFETY_VALVES_DESIGN.md}. The adapter does not
 * execute replay, traffic-split canary, or rollback. It only emits an
 * {@link ApprovalRequest} projection and a {@link DelegatedTaskEnvelope} so existing
 * approval and delegated-task foundations stay the source of truth.</p>
 */
public class BusinessSafetyValveAdapter {

    public enum ValveType { REPLAY, CANARY, ROLLBACK }

    private final BusinessApprovalAdapter approvalAdapter;

    public BusinessSafetyValveAdapter() {
        this(new BusinessApprovalAdapter());
    }

    public BusinessSafetyValveAdapter(BusinessApprovalAdapter approvalAdapter) {
        this.approvalAdapter = Objects.requireNonNull(approvalAdapter, "approvalAdapter");
    }

    public SafetyValveProjection toReplayRequest(BusinessRunRecord run, String requestedBy) {
        Objects.requireNonNull(run, "run");
        String operation = "safety-valve:replay:" + nonBlank(run.getRunId(), "unknown");
        ApprovalRequest request = new ApprovalRequest(
            ApprovalSystem.ApprovalType.CODE_EXECUTION,
            operation,
            "Replay business run via foundation IntentOrchestrator. Foundation ref: " + nonBlank(run.getTechnicalTraceRef(), "(none)"),
            false
        );
        BusinessApprovalRecord card = approvalAdapter.fromApprovalRequest(run.getWorkspaceId(), run.getTeamId(), request);
        DelegatedTaskEnvelope envelope = envelope(
            run.getWorkspaceId(),
            ValveType.REPLAY,
            "Replay business run " + run.getRunId(),
            "safety-valve:replay:" + run.getRunId(),
            run.getTeamId(),
            "safety-valve-replay",
            Map.of(
                "runId", String.valueOf(run.getRunId()),
                "intentRunRef", String.valueOf(run.getTechnicalTraceRef()),
                "workspaceId", String.valueOf(run.getWorkspaceId())
            ),
            "MEDIUM"
        );
        return projection(run.getWorkspaceId(), ValveType.REPLAY, requestedBy, request, card, envelope, references(run, null, null, null));
    }

    public SafetyValveProjection toCanaryProposal(TeamBlueprintRecord team, TeamBlueprintVersion version, String requestedBy) {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(version, "version");
        String operation = "safety-valve:canary:" + team.getTeamId() + "@v" + version.getVersion();
        ApprovalRequest request = new ApprovalRequest(
            ApprovalSystem.ApprovalType.CODE_EXECUTION,
            operation,
            "Promote team blueprint version to CANARY before ACTIVE. Foundation must compile via TeamBlueprintCompiler under approval.",
            true
        );
        BusinessApprovalRecord card = approvalAdapter.fromApprovalRequest(team.getWorkspaceId(), team.getTeamId(), request);
        DelegatedTaskEnvelope envelope = envelope(
            team.getWorkspaceId(),
            ValveType.CANARY,
            "Canary review for team " + team.getTeamId() + " v" + version.getVersion(),
            "safety-valve:canary:" + team.getTeamId() + "@v" + version.getVersion(),
            team.getTeamId(),
            "safety-valve-canary",
            Map.of(
                "teamId", String.valueOf(team.getTeamId()),
                "version", String.valueOf(version.getVersion()),
                "promptAssetRefs", version.getPromptAssetRefs() != null ? version.getPromptAssetRefs() : List.of()
            ),
            "HIGH"
        );
        return projection(team.getWorkspaceId(), ValveType.CANARY, requestedBy, request, card, envelope, references(null, team, version, null));
    }

    public SafetyValveProjection toRollbackProposal(TeamBlueprintRecord team, int fromVersion, int toVersion, String requestedBy) {
        Objects.requireNonNull(team, "team");
        if (fromVersion <= 0 || toVersion <= 0 || fromVersion == toVersion) {
            throw new IllegalArgumentException("Rollback requires distinct positive fromVersion and toVersion");
        }
        String operation = "safety-valve:rollback:" + team.getTeamId() + ":" + fromVersion + "->" + toVersion;
        ApprovalRequest request = new ApprovalRequest(
            ApprovalSystem.ApprovalType.CODE_EXECUTION,
            operation,
            "Roll back team blueprint version. Foundation versioning + ApprovalSystem must approve. No autonomous rollback.",
            true
        );
        BusinessApprovalRecord card = approvalAdapter.fromApprovalRequest(team.getWorkspaceId(), team.getTeamId(), request);
        DelegatedTaskEnvelope envelope = envelope(
            team.getWorkspaceId(),
            ValveType.ROLLBACK,
            "Rollback review for team " + team.getTeamId() + " " + fromVersion + " -> " + toVersion,
            operation,
            team.getTeamId(),
            "safety-valve-rollback",
            Map.of(
                "teamId", String.valueOf(team.getTeamId()),
                "fromVersion", fromVersion,
                "toVersion", toVersion
            ),
            "HIGH"
        );
        return projection(team.getWorkspaceId(), ValveType.ROLLBACK, requestedBy, request, card, envelope,
            references(null, team, null, Map.of("fromVersion", fromVersion, "toVersion", toVersion)));
    }

    private DelegatedTaskEnvelope envelope(String workspaceId, ValveType valve, String description, String runId,
                                           String teamId, String profile, Map<String, Object> evidence, String risk) {
        Map<String, Object> context = new LinkedHashMap<>(ContextPressureReport.none().toMap());
        context.put("source", "business-safety-valve");
        context.put("valve_type", valve.name().toLowerCase());
        context.put("workspace_id", workspaceId);
        context.put("evidence", evidence);
        context.put("risk", risk);
        return new DelegatedTaskEnvelope(
            description,
            runId,
            teamId,
            profile,
            "Governed safety-valve review required before applying " + valve.name().toLowerCase(),
            context,
            Instant.now()
        );
    }

    private SafetyValveProjection projection(String workspaceId, ValveType valve, String requestedBy,
                                             ApprovalRequest approvalRequest, BusinessApprovalRecord card,
                                             DelegatedTaskEnvelope envelope, Map<String, Object> references) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "foundation:safety-valve");
        metadata.put("valveType", valve.name());
        metadata.put("workspaceId", workspaceId);
        metadata.put("requestedBy", requestedBy != null && !requestedBy.isBlank() ? requestedBy : "business-portal");
        metadata.put("approvalDangerous", approvalRequest.isDangerous());
        return new SafetyValveProjection(
            workspaceId,
            valve,
            metadata,
            approvalRequest,
            card,
            envelope,
            references
        );
    }

    private Map<String, Object> references(BusinessRunRecord run, TeamBlueprintRecord team, TeamBlueprintVersion version,
                                           Map<String, Object> extra) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (run != null) {
            if (run.getTechnicalTraceRef() != null) map.put("intentRunRef", run.getTechnicalTraceRef());
            if (run.getRunId() != null) map.put("businessRunId", run.getRunId());
        }
        if (team != null) {
            if (team.getTeamId() != null) map.put("teamId", team.getTeamId());
        }
        if (version != null) {
            map.put("targetTeamBlueprintVersion", version.getVersion());
        }
        if (extra != null) map.putAll(extra);
        return map;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /** Read-only safety-valve projection. */
    public record SafetyValveProjection(
        String workspaceId,
        ValveType valveType,
        Map<String, Object> metadata,
        ApprovalRequest approvalRequest,
        BusinessApprovalRecord approvalCard,
        DelegatedTaskEnvelope delegatedTaskEnvelope,
        Map<String, Object> references
    ) {
        public SafetyValveProjection {
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
            references = references != null ? Map.copyOf(references) : Map.of();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("workspaceId", workspaceId);
            map.put("valveType", valveType.name());
            map.put("metadata", metadata);
            map.put("approvalOperation", approvalRequest.getOperation());
            map.put("approvalDangerous", approvalRequest.isDangerous());
            map.put("approvalCard", approvalCard);
            map.put("delegatedTaskEnvelope", delegatedTaskEnvelope.toMap());
            map.put("references", references);
            return map;
        }
    }
}
