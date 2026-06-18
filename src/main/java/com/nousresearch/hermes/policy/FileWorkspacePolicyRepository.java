package com.nousresearch.hermes.policy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** File-based repository for workspace policy records. */
public class FileWorkspacePolicyRepository {
    private final Path workspacesRoot;

    public FileWorkspacePolicyRepository(Path workspacesRoot) {
        this.workspacesRoot = workspacesRoot;
    }

    public void save(WorkspacePolicyRecord record) {
        Path file = policyFile(record.getWorkspaceId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(record));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save policy for " + record.getWorkspaceId(), e);
        }
    }

    public Optional<WorkspacePolicyRecord> findByWorkspaceId(String workspaceId) {
        Path file = policyFile(workspaceId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file);
            return Optional.of(JSON.parseObject(content, WorkspacePolicyRecord.class));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load policy for " + workspaceId, e);
        }
    }

    public void delete(String workspaceId) {
        Path file = policyFile(workspaceId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete policy for " + workspaceId, e);
        }
    }

    private Path policyFile(String workspaceId) {
        return workspacesRoot.resolve(workspaceId).resolve("policy.json");
    }
}
