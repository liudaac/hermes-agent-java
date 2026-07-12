package com.nousresearch.hermes.blueprint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nousresearch.hermes.common.PathIds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** File-backed repository for versioned team blueprints. */
public class FileTeamBlueprintRepository {
    private final Path workspacesRoot;
    private final ObjectMapper mapper;

    public FileTeamBlueprintRepository(Path workspacesRoot) {
        this.workspacesRoot = workspacesRoot;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(TeamBlueprintRecord record) {
        try {
            Path dir = teamDir(record.getWorkspaceId());
            Files.createDirectories(dir);
            writeAtomic(dir.resolve(sanitize(record.getTeamId()) + ".json"), record);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save team blueprint: " + record.getTeamId(), e);
        }
    }

    public Optional<TeamBlueprintRecord> findById(String workspaceId, String teamId) {
        Path file = teamDir(workspaceId).resolve(sanitize(teamId) + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), TeamBlueprintRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load team blueprint: " + workspaceId + "/" + teamId, e);
        }
    }

    public List<TeamBlueprintRecord> list(String workspaceId) {
        Path dir = teamDir(workspaceId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            List<TeamBlueprintRecord> records = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(file -> {
                    try {
                        records.add(mapper.readValue(file.toFile(), TeamBlueprintRecord.class));
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load team blueprint file: " + file, e);
                    }
                });
            records.sort(Comparator.comparing(TeamBlueprintRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list team blueprints for workspace: " + workspaceId, e);
        }
    }

    public boolean exists(String workspaceId, String teamId) {
        return Files.exists(teamDir(workspaceId).resolve(sanitize(teamId) + ".json"));
    }

    private Path teamDir(String workspaceId) {
        return workspacesRoot.resolve(sanitize(workspaceId)).resolve("team-blueprints");
    }

    private void writeAtomic(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), value);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sanitize(String id) {
        return PathIds.strictPathSegment(id, "id");
    }
}
