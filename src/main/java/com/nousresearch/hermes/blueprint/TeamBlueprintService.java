package com.nousresearch.hermes.blueprint;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceService;
import com.nousresearch.hermes.prompt.PromptAssetService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Business service for versioned team blueprints. */
public class TeamBlueprintService {
    private final FileTeamBlueprintRepository repository;
    private final WorkspaceService workspaceService;
    private final PromptAssetService promptAssetService;

    public TeamBlueprintService(WorkspaceService workspaceService) {
        this(new FileTeamBlueprintRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService,
            new PromptAssetService(workspaceService));
    }

    public TeamBlueprintService(Path workspacesRoot, WorkspaceService workspaceService) {
        this(new FileTeamBlueprintRepository(workspacesRoot), workspaceService,
            new PromptAssetService(workspacesRoot, workspaceService));
    }

    public TeamBlueprintService(FileTeamBlueprintRepository repository, WorkspaceService workspaceService) {
        this(repository, workspaceService, new PromptAssetService(workspaceService));
    }

    public TeamBlueprintService(FileTeamBlueprintRepository repository, WorkspaceService workspaceService, PromptAssetService promptAssetService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.promptAssetService = promptAssetService;
    }

    public TeamBlueprintRecord createTeamBlueprint(String workspaceId, String teamId, String name, String description,
                                                   String scenario, String scenarioId, List<AgentBlueprintRecord> agents,
                                                   List<String> promptAssetRefs, String operatingManual,
                                                   Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        validatePromptAssetRefs(workspaceId, promptAssetRefs);
        validateId(teamId, "teamId");
        if (repository.exists(workspaceId, teamId)) {
            throw new TeamBlueprintAlreadyExistsException(workspaceId, teamId);
        }

        Instant now = Instant.now();
        TeamBlueprintVersion v1 = new TeamBlueprintVersion()
            .setVersion(1)
            .setStatus("ACTIVE")
            .setChangeSummary("Initial business team blueprint")
            .setAgents(agents)
            .setPromptAssetRefs(promptAssetRefs)
            .setOperatingManual(operatingManual)
            .setCreatedAt(now)
            .setActivatedAt(now);

        TeamBlueprintRecord record = new TeamBlueprintRecord()
            .setWorkspaceId(workspaceId)
            .setTeamId(teamId)
            .setName(name != null && !name.isBlank() ? name : teamId)
            .setDescription(description)
            .setScenario(scenario)
            .setScenarioId(scenarioId)
            .setActiveVersion(1)
            .setVersions(List.of(v1))
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    public TeamBlueprintVersion createDraftVersion(String workspaceId, String teamId, String changeSummary,
                                                   List<AgentBlueprintRecord> agents,
                                                   List<String> promptAssetRefs,
                                                   String operatingManual,
                                                   Map<String, Object> metadata) {
        TeamBlueprintRecord record = requireTeamBlueprint(workspaceId, teamId);
        validatePromptAssetRefs(workspaceId, promptAssetRefs);
        int nextVersion = record.getVersions().stream().mapToInt(TeamBlueprintVersion::getVersion).max().orElse(0) + 1;
        TeamBlueprintVersion version = new TeamBlueprintVersion()
            .setVersion(nextVersion)
            .setStatus("DRAFT")
            .setChangeSummary(changeSummary != null && !changeSummary.isBlank() ? changeSummary : "Draft update")
            .setAgents(agents)
            .setPromptAssetRefs(promptAssetRefs)
            .setOperatingManual(operatingManual)
            .setMetadata(metadata)
            .setCreatedAt(Instant.now());
        record.getVersions().add(version);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return version;
    }

    public TeamBlueprintRecord activateVersion(String workspaceId, String teamId, int versionNumber) {
        TeamBlueprintRecord record = requireTeamBlueprint(workspaceId, teamId);
        TeamBlueprintVersion target = record.getVersions().stream()
            .filter(version -> version.getVersion() == versionNumber)
            .findFirst()
            .orElseThrow(() -> new TeamBlueprintVersionNotFoundException(workspaceId, teamId, versionNumber));

        Instant now = Instant.now();
        for (TeamBlueprintVersion version : record.getVersions()) {
            if (version.getVersion() == versionNumber) {
                version.setStatus("ACTIVE");
                version.setActivatedAt(now);
            } else if ("ACTIVE".equals(version.getStatus())) {
                version.setStatus("INACTIVE");
            }
        }
        record.setActiveVersion(target.getVersion());
        record.setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    public List<TeamBlueprintRecord> listTeamBlueprints(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.list(workspaceId);
    }

    public Optional<TeamBlueprintRecord> getTeamBlueprint(String workspaceId, String teamId) {
        return repository.findById(workspaceId, teamId);
    }

    public TeamBlueprintRecord requireTeamBlueprint(String workspaceId, String teamId) {
        workspaceService.requireWorkspace(workspaceId);
        return getTeamBlueprint(workspaceId, teamId)
            .orElseThrow(() -> new TeamBlueprintNotFoundException(workspaceId, teamId));
    }

    private void validatePromptAssetRefs(String workspaceId, List<String> promptAssetRefs) {
        if (promptAssetRefs == null || promptAssetRefs.isEmpty()) {
            return;
        }
        for (String ref : promptAssetRefs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            PromptAssetRef promptRef = parsePromptAssetRef(ref);
            if (promptRef == null) {
                throw new IllegalArgumentException("Unsupported prompt asset ref: " + ref + ". Expected format: prompt://{assetId} or prompt://{assetId}#v{version}");
            }
            if (promptRef.version() != null) {
                promptAssetService.requireVersion(workspaceId, promptRef.assetId(), promptRef.version());
            } else {
                promptAssetService.requirePromptAsset(workspaceId, promptRef.assetId());
            }
        }
    }

    private static PromptAssetRef parsePromptAssetRef(String ref) {
        String prefix = "prompt://";
        if (!ref.startsWith(prefix)) {
            return null;
        }
        String value = ref.substring(prefix.length()).trim();
        if (value.isBlank() || value.contains("/")) {
            return null;
        }
        String assetId = value;
        Integer version = null;
        int versionSeparator = value.indexOf("#v");
        if (versionSeparator >= 0) {
            assetId = value.substring(0, versionSeparator);
            String versionText = value.substring(versionSeparator + 2);
            if (assetId.isBlank() || versionText.isBlank() || versionText.contains("#")) {
                return null;
            }
            try {
                version = Integer.parseInt(versionText);
            } catch (NumberFormatException e) {
                return null;
            }
            if (version <= 0) {
                return null;
            }
        } else if (value.contains("#")) {
            return null;
        }
        return new PromptAssetRef(assetId, version);
    }

    private record PromptAssetRef(String assetId, Integer version) {}

    private static void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}")) {
            throw new IllegalArgumentException(field + " must be 2-64 chars and contain only letters, numbers, dot, underscore or dash");
        }
    }

    public static class TeamBlueprintAlreadyExistsException extends RuntimeException {
        public TeamBlueprintAlreadyExistsException(String workspaceId, String teamId) { super("Team blueprint already exists: " + workspaceId + "/" + teamId); }
    }

    public static class TeamBlueprintNotFoundException extends RuntimeException {
        public TeamBlueprintNotFoundException(String workspaceId, String teamId) { super("Team blueprint not found: " + workspaceId + "/" + teamId); }
    }

    public static class TeamBlueprintVersionNotFoundException extends RuntimeException {
        public TeamBlueprintVersionNotFoundException(String workspaceId, String teamId, int version) { super("Team blueprint version not found: " + workspaceId + "/" + teamId + "#" + version); }
    }
}
