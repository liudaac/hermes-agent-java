package com.nousresearch.hermes.prompt;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
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
        PromptAssetRecord record = new PromptAssetRecord()
            .setWorkspaceId(workspaceId)
            .setAssetId(assetId)
            .setName(name != null && !name.isBlank() ? name : assetId)
            .setPurpose(purpose)
            .setContent(content != null ? content : "")
            .setVersion(1)
            .setStatus("ACTIVE")
            .setTags(tags)
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
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
}
