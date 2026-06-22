package com.nousresearch.hermes.memory;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Active Memory service.
 *
 * <p>Provides knowledge storage and recall. The current implementation uses
 * file-based tag/keyword matching as the default fallback. The recall method
 * is designed to be pluggable — future implementations can swap in vector DB
 * semantic search without changing the service interface.</p>
 */
public class BusinessMemoryNoteService {
    private final FileActiveMemoryRepository repository;
    private final WorkspaceService workspaceService;

    public BusinessMemoryNoteService(WorkspaceService workspaceService) {
        this(new FileActiveMemoryRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService);
    }

    public BusinessMemoryNoteService(Path workspacesRoot, WorkspaceService workspaceService) {
        this(new FileActiveMemoryRepository(workspacesRoot), workspaceService);
    }

    public BusinessMemoryNoteService(FileActiveMemoryRepository repository, WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    public ActiveMemoryRecord createMemory(String workspaceId, String memoryId, String type, String title,
                                           String content, List<String> tags, List<String> scenarioIds,
                                           List<String> teamIds, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        Instant now = Instant.now();
        ActiveMemoryRecord record = new ActiveMemoryRecord()
            .setWorkspaceId(workspaceId)
            .setMemoryId(memoryId != null && !memoryId.isBlank() ? memoryId : "mem-" + UUID.randomUUID().toString().substring(0, 8))
            .setType(type != null ? type : ActiveMemoryRecord.TYPE_RULE)
            .setTitle(title)
            .setContent(content)
            .setTags(tags != null ? tags : List.of())
            .setScenarioIds(scenarioIds != null ? scenarioIds : List.of())
            .setTeamIds(teamIds != null ? teamIds : List.of())
            .setRecallCount(0)
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    public ActiveMemoryRecord getMemory(String workspaceId, String memoryId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.find(workspaceId, memoryId)
            .orElseThrow(() -> new MemoryNotFoundException(workspaceId, memoryId));
    }

    public List<ActiveMemoryRecord> listMemories(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.listByWorkspace(workspaceId);
    }

    public ActiveMemoryRecord updateMemory(String workspaceId, String memoryId, String title, String content,
                                           List<String> tags, List<String> scenarioIds, List<String> teamIds,
                                           Map<String, Object> metadata) {
        ActiveMemoryRecord record = getMemory(workspaceId, memoryId);
        if (title != null) record.setTitle(title);
        if (content != null) record.setContent(content);
        if (tags != null) record.setTags(tags);
        if (scenarioIds != null) record.setScenarioIds(scenarioIds);
        if (teamIds != null) record.setTeamIds(teamIds);
        if (metadata != null) record.setMetadata(metadata);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return record;
    }

    public void deleteMemory(String workspaceId, String memoryId) {
        workspaceService.requireWorkspace(workspaceId);
        repository.delete(workspaceId, memoryId);
    }

    /**
     * Recall relevant memories for a given context.
     * Strategy: scenario match > tag match > keyword search.
     */
    public List<ActiveMemoryRecord> recall(String workspaceId, String scenarioId, List<String> tags, String query, int limit) {
        workspaceService.requireWorkspace(workspaceId);
        List<ActiveMemoryRecord> results = new java.util.ArrayList<>();
        // 1. Scenario-linked memories
        if (scenarioId != null && !scenarioId.isBlank()) {
            results.addAll(repository.recallByScenario(workspaceId, scenarioId, limit));
        }
        // 2. Tag-based memories
        if (results.size() < limit && tags != null && !tags.isEmpty()) {
            results.addAll(repository.recallByTags(workspaceId, tags, limit - results.size()));
        }
        // 3. Keyword search
        if (results.size() < limit && query != null && !query.isBlank()) {
            results.addAll(repository.search(workspaceId, query, limit - results.size()));
        }
        // Deduplicate and update recall stats
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<ActiveMemoryRecord> deduped = new java.util.ArrayList<>();
        for (ActiveMemoryRecord m : results) {
            if (seen.add(m.getMemoryId())) {
                m.setRecallCount(m.getRecallCount() + 1);
                m.setLastRecalledAt(Instant.now());
                repository.save(m);
                deduped.add(m);
            }
        }
        return deduped;
    }

    public static class MemoryNotFoundException extends RuntimeException {
        public MemoryNotFoundException(String workspaceId, String memoryId) {
            super("Memory not found: " + memoryId + " in workspace " + workspaceId);
        }
    }
}
