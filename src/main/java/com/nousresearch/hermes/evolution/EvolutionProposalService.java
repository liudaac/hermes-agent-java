package com.nousresearch.hermes.evolution;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.blueprint.TeamBlueprintVersion;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Business service for workspace-scoped evolution proposals. */
public class EvolutionProposalService {
    public static final String DRAFT = "DRAFT";
    public static final String EVALUATING = "EVALUATING";
    public static final String NEEDS_APPROVAL = "NEEDS_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String APPLIED = "APPLIED";

    private final FileEvolutionProposalRepository repository;
    private final WorkspaceService workspaceService;
    private final TeamBlueprintService teamBlueprintService;

    public EvolutionProposalService(WorkspaceService workspaceService) {
        this(new FileEvolutionProposalRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService, null);
    }

    public EvolutionProposalService(WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileEvolutionProposalRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService, teamBlueprintService);
    }

    public EvolutionProposalService(Path workspacesRoot, WorkspaceService workspaceService) {
        this(new FileEvolutionProposalRepository(workspacesRoot), workspaceService, null);
    }

    public EvolutionProposalService(Path workspacesRoot, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileEvolutionProposalRepository(workspacesRoot), workspaceService, teamBlueprintService);
    }

    public EvolutionProposalService(FileEvolutionProposalRepository repository, WorkspaceService workspaceService) {
        this(repository, workspaceService, null);
    }

    public EvolutionProposalService(FileEvolutionProposalRepository repository, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.teamBlueprintService = teamBlueprintService;
    }

    public EvolutionProposalRecord createProposal(String workspaceId, String proposalId, String scenarioId, String teamId,
                                                  String sourceInsightId, String title, String finding,
                                                  String proposedChange, String expectedBenefit,
                                                  Map<String, Object> evidence, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        String finalProposalId = (proposalId == null || proposalId.isBlank())
            ? "evp-" + UUID.randomUUID().toString().substring(0, 10)
            : proposalId.trim();
        validateId(finalProposalId, "proposalId");
        if (repository.findById(workspaceId, finalProposalId).isPresent()) {
            throw new EvolutionProposalAlreadyExistsException(workspaceId, finalProposalId);
        }
        Instant now = Instant.now();
        EvolutionProposalRecord record = new EvolutionProposalRecord()
            .setWorkspaceId(workspaceId)
            .setProposalId(finalProposalId)
            .setScenarioId(blankToNull(scenarioId))
            .setTeamId(blankToNull(teamId))
            .setSourceInsightId(blankToNull(sourceInsightId))
            .setTitle(requiredOrDefault(title, "业务进化提案"))
            .setFinding(finding)
            .setProposedChange(requiredOrDefault(proposedChange, "请补充建议变更内容。"))
            .setExpectedBenefit(expectedBenefit)
            .setStatus(DRAFT)
            .setEvidence(evidence)
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    public List<EvolutionProposalRecord> listProposals(String workspaceId, String status) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.list(workspaceId, normalizeStatusFilter(status));
    }

    public EvolutionProposalRecord requireProposal(String workspaceId, String proposalId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.findById(workspaceId, proposalId)
            .orElseThrow(() -> new EvolutionProposalNotFoundException(workspaceId, proposalId));
    }

    public EvolutionProposalRecord startEvaluation(String workspaceId, String proposalId) {
        EvolutionProposalRecord record = requireStatus(workspaceId, proposalId, DRAFT);
        return updateStatus(record, EVALUATING);
    }

    public EvolutionProposalRecord requestApproval(String workspaceId, String proposalId, String approvalId) {
        EvolutionProposalRecord record = requireProposal(workspaceId, proposalId);
        if (!DRAFT.equals(record.getStatus()) && !EVALUATING.equals(record.getStatus())) {
            throw new InvalidEvolutionProposalTransitionException(record.getStatus(), NEEDS_APPROVAL);
        }
        record.setApprovalId(blankToNull(approvalId));
        return updateStatus(record, NEEDS_APPROVAL);
    }

    public EvolutionProposalRecord approve(String workspaceId, String proposalId, String actor, String reason) {
        EvolutionProposalRecord record = requireProposal(workspaceId, proposalId);
        if (!NEEDS_APPROVAL.equals(record.getStatus()) && !EVALUATING.equals(record.getStatus()) && !DRAFT.equals(record.getStatus())) {
            throw new InvalidEvolutionProposalTransitionException(record.getStatus(), APPROVED);
        }
        appendResolutionMetadata(record, "approvedBy", actor, reason);
        return updateStatus(record, APPROVED);
    }

    public EvolutionProposalRecord reject(String workspaceId, String proposalId, String actor, String reason) {
        EvolutionProposalRecord record = requireProposal(workspaceId, proposalId);
        if (APPLIED.equals(record.getStatus())) {
            throw new InvalidEvolutionProposalTransitionException(record.getStatus(), REJECTED);
        }
        appendResolutionMetadata(record, "rejectedBy", actor, reason);
        return updateStatus(record, REJECTED);
    }

    public EvolutionProposalRecord apply(String workspaceId, String proposalId, String targetTeamId) {
        EvolutionProposalRecord record = requireStatus(workspaceId, proposalId, APPROVED);
        String teamId = firstNonBlank(targetTeamId, record.getTargetTeamId(), record.getTeamId());
        if (teamBlueprintService != null && teamId != null) {
            TeamBlueprintVersion draft = teamBlueprintService.createDraftVersion(
                workspaceId,
                teamId,
                "Evolution proposal " + record.getProposalId() + ": " + record.getTitle(),
                null,
                null,
                record.getProposedChange(),
                Map.of(
                    "source", "evolution-proposal",
                    "proposalId", record.getProposalId(),
                    "expectedBenefit", record.getExpectedBenefit() == null ? "" : record.getExpectedBenefit()
                )
            );
            record.setTargetTeamId(teamId);
            record.setTargetDraftVersion(draft.getVersion());
        } else if (teamId != null) {
            record.setTargetTeamId(teamId);
        }
        Instant now = Instant.now();
        record.setStatus(APPLIED)
            .setAppliedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    private EvolutionProposalRecord requireStatus(String workspaceId, String proposalId, String expectedStatus) {
        EvolutionProposalRecord record = requireProposal(workspaceId, proposalId);
        if (!expectedStatus.equals(record.getStatus())) {
            throw new InvalidEvolutionProposalTransitionException(record.getStatus(), expectedStatus);
        }
        return record;
    }

    private EvolutionProposalRecord updateStatus(EvolutionProposalRecord record, String status) {
        record.setStatus(status).setUpdatedAt(Instant.now());
        repository.save(record);
        return record;
    }

    private static void appendResolutionMetadata(EvolutionProposalRecord record, String actorKey, String actor, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>(record.getMetadata());
        metadata.put(actorKey, actor != null && !actor.isBlank() ? actor : "business-user");
        if (reason != null && !reason.isBlank()) {
            metadata.put("resolutionReason", reason);
        }
        metadata.put("resolvedAt", Instant.now().toString());
        record.setMetadata(metadata);
    }

    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        validateStatus(normalized);
        return normalized;
    }

    private static void validateStatus(String status) {
        switch (status) {
            case DRAFT, EVALUATING, NEEDS_APPROVAL, APPROVED, REJECTED, APPLIED -> { }
            default -> throw new IllegalArgumentException("Unsupported evolution proposal status: " + status);
        }
    }

    private static void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}")) {
            throw new IllegalArgumentException(field + " must be 2-64 chars and contain only letters, numbers, dot, underscore or dash");
        }
    }

    private static String requiredOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public static class EvolutionProposalAlreadyExistsException extends RuntimeException {
        public EvolutionProposalAlreadyExistsException(String workspaceId, String proposalId) { super("Evolution proposal already exists: " + workspaceId + "/" + proposalId); }
    }

    public static class EvolutionProposalNotFoundException extends RuntimeException {
        public EvolutionProposalNotFoundException(String workspaceId, String proposalId) { super("Evolution proposal not found: " + workspaceId + "/" + proposalId); }
    }

    public static class InvalidEvolutionProposalTransitionException extends RuntimeException {
        public InvalidEvolutionProposalTransitionException(String from, String to) { super("Invalid evolution proposal transition: " + from + " -> " + to); }
    }
}
