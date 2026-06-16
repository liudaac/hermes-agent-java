package com.nousresearch.hermes.prompt;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Workspace-scoped prompt asset service. */
public class PromptAssetService {
    private final FilePromptAssetRepository repository;
    private final WorkspaceService workspaceService;

    public PromptAssetService(WorkspaceService workspaceService) {
        this(new FilePromptAssetRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService);
    }

    public PromptAssetService(Path workspacesRoot, WorkspaceService workspaceService) {
        this(new FilePromptAssetRepository(workspacesRoot), workspaceService);
    }

    public PromptAssetService(FilePromptAssetRepository repository, WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    public PromptAssetRecord createPromptAsset(String workspaceId, String assetId, String name, String purpose,
                                               String content, List<String> tags, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        validateId(assetId, "assetId");
        if (repository.exists(workspaceId, assetId)) {
            throw new PromptAssetAlreadyExistsException(workspaceId, assetId);
        }
        Instant now = Instant.now();
        String initialContent = content != null ? content : "";
        PromptAssetVersion v1 = new PromptAssetVersion()
            .setVersion(1)
            .setStatus("ACTIVE")
            .setContent(initialContent)
            .setChangeSummary("Initial prompt asset")
            .setMetadata(copyMetadata(metadata))
            .setCreatedAt(now)
            .setActivatedAt(now);
        PromptAssetRecord record = new PromptAssetRecord()
            .setWorkspaceId(workspaceId)
            .setAssetId(assetId)
            .setName(name != null && !name.isBlank() ? name : assetId)
            .setPurpose(purpose)
            .setContent(initialContent)
            .setVersion(1)
            .setActiveVersion(1)
            .setStatus("ACTIVE")
            .setTags(tags)
            .setVersions(new ArrayList<>(List.of(v1)))
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }


    public PromptAssetVersion createDraftVersion(String workspaceId, String assetId, String content,
                                                 String changeSummary, Map<String, ?> metadata) {
        PromptAssetRecord record = requirePromptAsset(workspaceId, assetId);
        ensureVersions(record);
        int nextVersion = record.getVersions().stream().mapToInt(PromptAssetVersion::getVersion).max().orElse(0) + 1;
        PromptAssetVersion version = new PromptAssetVersion()
            .setVersion(nextVersion)
            .setStatus("DRAFT")
            .setContent(content != null ? content : "")
            .setChangeSummary(changeSummary != null && !changeSummary.isBlank() ? changeSummary : "Draft update")
            .setMetadata(copyMetadata(metadata))
            .setCreatedAt(Instant.now());
        record.getVersions().add(version);
        record.setVersion(nextVersion);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return version;
    }

    public PromptAssetRecord activateVersion(String workspaceId, String assetId, int versionNumber) {
        PromptAssetRecord record = requirePromptAsset(workspaceId, assetId);
        ensureVersions(record);
        PromptAssetVersion target = record.getVersions().stream()
            .filter(version -> version.getVersion() == versionNumber)
            .findFirst()
            .orElseThrow(() -> new PromptAssetVersionNotFoundException(workspaceId, assetId, versionNumber));

        Instant now = Instant.now();
        for (PromptAssetVersion version : record.getVersions()) {
            if (version.getVersion() == versionNumber) {
                version.setStatus("ACTIVE");
                version.setActivatedAt(now);
            } else if ("ACTIVE".equals(version.getStatus())) {
                version.setStatus("INACTIVE");
            }
        }
        record.setActiveVersion(target.getVersion());
        record.setContent(target.getContent() != null ? target.getContent() : "");
        record.setStatus("ACTIVE");
        record.setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    public PromptAssetVersion requireVersion(String workspaceId, String assetId, Integer versionNumber) {
        PromptAssetRecord record = requirePromptAsset(workspaceId, assetId);
        ensureVersions(record);
        int requestedVersion = versionNumber != null ? versionNumber : record.getActiveVersion();
        return record.getVersions().stream()
            .filter(version -> version.getVersion() == requestedVersion)
            .findFirst()
            .orElseThrow(() -> new PromptAssetVersionNotFoundException(workspaceId, assetId, requestedVersion));
    }

    public List<PromptAssetRecord> listPromptAssets(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.list(workspaceId);
    }

    public Optional<PromptAssetRecord> getPromptAsset(String workspaceId, String assetId) {
        return repository.findById(workspaceId, assetId);
    }

    public PromptAssetRecord requirePromptAsset(String workspaceId, String assetId) {
        workspaceService.requireWorkspace(workspaceId);
        return getPromptAsset(workspaceId, assetId).orElseThrow(() -> new PromptAssetNotFoundException(workspaceId, assetId));
    }


    private void ensureVersions(PromptAssetRecord record) {
        if (record.getVersions() != null && !record.getVersions().isEmpty()) {
            return;
        }
        Instant createdAt = record.getCreatedAt() != null ? record.getCreatedAt() : Instant.now();
        PromptAssetVersion legacyActive = new PromptAssetVersion()
            .setVersion(record.getActiveVersion() > 0 ? record.getActiveVersion() : Math.max(record.getVersion(), 1))
            .setStatus("ACTIVE")
            .setContent(record.getContent() != null ? record.getContent() : "")
            .setChangeSummary("Imported legacy prompt asset")
            .setMetadata(copyMetadata(record.getMetadata()))
            .setCreatedAt(createdAt)
            .setActivatedAt(createdAt);
        record.setVersions(new ArrayList<>(List.of(legacyActive)));
        if (record.getActiveVersion() <= 0) {
            record.setActiveVersion(legacyActive.getVersion());
        }
    }

    private static Map<String, Object> copyMetadata(Map<String, ?> metadata) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (key != null) {
                    copy.put(key, value);
                }
            });
        }
        return copy;
    }

    private static void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}")) {
            throw new IllegalArgumentException(field + " must be 2-64 chars and contain only letters, numbers, dot, underscore or dash");
        }
    }

    public static class PromptAssetAlreadyExistsException extends RuntimeException {
        public PromptAssetAlreadyExistsException(String workspaceId, String assetId) { super("Prompt asset already exists: " + workspaceId + "/" + assetId); }
    }

    public static class PromptAssetNotFoundException extends RuntimeException {
        public PromptAssetNotFoundException(String workspaceId, String assetId) { super("Prompt asset not found: " + workspaceId + "/" + assetId); }
    }

    public static class PromptAssetVersionNotFoundException extends RuntimeException {
        public PromptAssetVersionNotFoundException(String workspaceId, String assetId, int version) { super("Prompt asset version not found: " + workspaceId + "/" + assetId + "#v" + version); }
    }
}
