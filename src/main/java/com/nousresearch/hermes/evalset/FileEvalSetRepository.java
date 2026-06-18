package com.nousresearch.hermes.evalset;

import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** File-based repository for eval sets. */
public class FileEvalSetRepository {
    private final Path root;

    public FileEvalSetRepository(Path workspacesRoot) {
        this.root = workspacesRoot;
    }

    public void save(EvalSetRecord record) {
        Path file = evalSetFile(record.getWorkspaceId(), record.getScenarioId(), record.getEvalSetId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(record));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save eval set " + record.getEvalSetId(), e);
        }
    }

    public Optional<EvalSetRecord> find(String workspaceId, String scenarioId, String evalSetId) {
        Path file = evalSetFile(workspaceId, scenarioId, evalSetId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(JSON.parseObject(Files.readString(file), EvalSetRecord.class));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load eval set " + evalSetId, e);
        }
    }

    public List<EvalSetRecord> listByScenario(String workspaceId, String scenarioId) {
        Path dir = scenarioEvalDir(workspaceId, scenarioId);
        if (!Files.exists(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(".json"))
                .map(p -> {
                    try {
                        return JSON.parseObject(Files.readString(p), EvalSetRecord.class);
                    } catch (IOException e) {
                        return null;
                    }
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list eval sets", e);
        }
    }

    public void delete(String workspaceId, String scenarioId, String evalSetId) {
        try {
            Files.deleteIfExists(evalSetFile(workspaceId, scenarioId, evalSetId));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete eval set " + evalSetId, e);
        }
    }

    private Path evalSetFile(String workspaceId, String scenarioId, String evalSetId) {
        return scenarioEvalDir(workspaceId, scenarioId).resolve(evalSetId + ".json");
    }

    private Path scenarioEvalDir(String workspaceId, String scenarioId) {
        return root.resolve(workspaceId).resolve("scenarios").resolve(scenarioId).resolve("evalsets");
    }
}
