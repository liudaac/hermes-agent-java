package com.nousresearch.hermes.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** File-backed repository for business workspaces. */
public class FileWorkspaceRepository {
    private final Path rootDir;
    private final ObjectMapper mapper;

    public FileWorkspaceRepository(Path rootDir) {
        this.rootDir = rootDir;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(WorkspaceRecord record) {
        try {
            Path dir = workspaceDir(record.getWorkspaceId());
            Files.createDirectories(dir);
            writeAtomic(dir.resolve("workspace.json"), record);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save workspace: " + record.getWorkspaceId(), e);
        }
    }

    public Optional<WorkspaceRecord> findById(String workspaceId) {
        Path file = workspaceDir(workspaceId).resolve("workspace.json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), WorkspaceRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load workspace: " + workspaceId, e);
        }
    }

    public List<WorkspaceRecord> list() {
        if (!Files.exists(rootDir)) {
            return List.of();
        }
        try (var stream = Files.list(rootDir)) {
            List<WorkspaceRecord> records = new ArrayList<>();
            stream.filter(Files::isDirectory)
                .map(path -> path.resolve("workspace.json"))
                .filter(Files::exists)
                .forEach(file -> {
                    try {
                        records.add(mapper.readValue(file.toFile(), WorkspaceRecord.class));
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load workspace file: " + file, e);
                    }
                });
            records.sort(Comparator.comparing(WorkspaceRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list workspaces", e);
        }
    }

    public boolean exists(String workspaceId) {
        return Files.exists(workspaceDir(workspaceId).resolve("workspace.json"));
    }

    Path workspaceDir(String workspaceId) {
        return rootDir.resolve(sanitize(workspaceId));
    }

    private void writeAtomic(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), value);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sanitize(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
