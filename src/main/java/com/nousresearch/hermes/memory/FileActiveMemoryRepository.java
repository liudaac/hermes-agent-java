package com.nousresearch.hermes.memory;

import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * File-based Active Memory repository.
 *
 * <p>This is the default/fallback implementation. Future extensions can
 * replace this with vector DB-backed storage (e.g. Chroma, Milvus, pgvector)
 * by implementing the same operations.</p>
 */
public class FileActiveMemoryRepository {
    private final Path root;

    public FileActiveMemoryRepository(Path workspacesRoot) {
        this.root = workspacesRoot;
    }

    public void save(ActiveMemoryRecord record) {
        Path file = memoryFile(record.getWorkspaceId(), record.getMemoryId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(record));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save memory " + record.getMemoryId(), e);
        }
    }

    public Optional<ActiveMemoryRecord> find(String workspaceId, String memoryId) {
        Path file = memoryFile(workspaceId, memoryId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(JSON.parseObject(Files.readString(file), ActiveMemoryRecord.class));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load memory " + memoryId, e);
        }
    }

    public List<ActiveMemoryRecord> listByWorkspace(String workspaceId) {
        Path dir = workspaceMemoryDir(workspaceId);
        if (!Files.exists(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(".json"))
                .map(p -> {
                    try {
                        return JSON.parseObject(Files.readString(p), ActiveMemoryRecord.class);
                    } catch (IOException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list memories", e);
        }
    }

    /**
     * Tag-based recall — exact match fallback.
     * Future extension: semantic search via vector similarity.
     */
    public List<ActiveMemoryRecord> recallByTags(String workspaceId, List<String> tags, int limit) {
        if (tags == null || tags.isEmpty()) return List.of();
        Set<String> tagSet = new HashSet<>(tags);
        return listByWorkspace(workspaceId).stream()
            .filter(m -> m.getTags() != null && !Collections.disjoint(new HashSet<>(m.getTags()), tagSet))
            .sorted(Comparator.comparingInt(ActiveMemoryRecord::getRecallCount).reversed())
            .limit(limit > 0 ? limit : 10)
            .collect(Collectors.toList());
    }

    /**
     * Recall by scenario association.
     */
    public List<ActiveMemoryRecord> recallByScenario(String workspaceId, String scenarioId, int limit) {
        return listByWorkspace(workspaceId).stream()
            .filter(m -> m.getScenarioIds() != null && m.getScenarioIds().contains(scenarioId))
            .sorted(Comparator.comparingInt(ActiveMemoryRecord::getRecallCount).reversed())
            .limit(limit > 0 ? limit : 10)
            .collect(Collectors.toList());
    }

    /**
     * Keyword search in title and content — simple contains match.
     * Future extension: full-text search via Elasticsearch / vector DB.
     */
    public List<ActiveMemoryRecord> search(String workspaceId, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase();
        return listByWorkspace(workspaceId).stream()
            .filter(m -> (m.getTitle() != null && m.getTitle().toLowerCase().contains(lower))
                || (m.getContent() != null && m.getContent().toLowerCase().contains(lower)))
            .sorted(Comparator.comparingInt(ActiveMemoryRecord::getRecallCount).reversed())
            .limit(limit > 0 ? limit : 10)
            .collect(Collectors.toList());
    }

    public void delete(String workspaceId, String memoryId) {
        try {
            Files.deleteIfExists(memoryFile(workspaceId, memoryId));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete memory " + memoryId, e);
        }
    }

    private Path memoryFile(String workspaceId, String memoryId) {
        return workspaceMemoryDir(workspaceId).resolve(memoryId + ".json");
    }

    private Path workspaceMemoryDir(String workspaceId) {
        return root.resolve(workspaceId).resolve("memory");
    }
}
