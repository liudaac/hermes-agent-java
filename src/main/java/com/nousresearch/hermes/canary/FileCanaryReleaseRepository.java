package com.nousresearch.hermes.canary;

import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** File-based repository for canary releases. */
public class FileCanaryReleaseRepository {
    private final Path root;

    public FileCanaryReleaseRepository(Path workspacesRoot) {
        this.root = workspacesRoot;
    }

    public void save(CanaryReleaseRecord record) {
        Path file = releaseFile(record.getWorkspaceId(), record.getTeamId(), record.getReleaseId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJSONString(record));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save canary release " + record.getReleaseId(), e);
        }
    }

    public Optional<CanaryReleaseRecord> find(String workspaceId, String teamId, String releaseId) {
        Path file = releaseFile(workspaceId, teamId, releaseId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(JSON.parseObject(Files.readString(file), CanaryReleaseRecord.class));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load canary release " + releaseId, e);
        }
    }

    public List<CanaryReleaseRecord> listByTeam(String workspaceId, String teamId) {
        Path dir = teamCanaryDir(workspaceId, teamId);
        if (!Files.exists(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(".json"))
                .map(p -> {
                    try {
                        return JSON.parseObject(Files.readString(p), CanaryReleaseRecord.class);
                    } catch (IOException e) {
                        return null;
                    }
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to list canary releases", e);
        }
    }

    public Optional<CanaryReleaseRecord> findActive(String workspaceId, String teamId) {
        return listByTeam(workspaceId, teamId).stream()
            .filter(r -> CanaryReleaseRecord.ACTIVE.equals(r.getStatus()))
            .findFirst();
    }

    private Path releaseFile(String workspaceId, String teamId, String releaseId) {
        return teamCanaryDir(workspaceId, teamId).resolve(releaseId + ".json");
    }

    private Path teamCanaryDir(String workspaceId, String teamId) {
        return root.resolve(workspaceId).resolve("team-blueprints").resolve(teamId).resolve("canary");
    }
}
