package com.nousresearch.hermes.scenario;

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

/** File-backed repository for business scenarios. */
public class FileScenarioRepository {
    private final Path workspacesRoot;
    private final ObjectMapper mapper;

    public FileScenarioRepository(Path workspacesRoot) {
        this.workspacesRoot = workspacesRoot;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(ScenarioRecord record) {
        try {
            Path dir = scenariosDir(record.getWorkspaceId());
            Files.createDirectories(dir);
            writeAtomic(dir.resolve(sanitize(record.getScenarioId()) + ".json"), record);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save scenario: " + record.getScenarioId(), e);
        }
    }

    public Optional<ScenarioRecord> findById(String workspaceId, String scenarioId) {
        Path file = scenariosDir(workspaceId).resolve(sanitize(scenarioId) + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), ScenarioRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load scenario: " + workspaceId + "/" + scenarioId, e);
        }
    }

    public List<ScenarioRecord> list(String workspaceId) {
        Path dir = scenariosDir(workspaceId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            List<ScenarioRecord> records = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(file -> {
                    try {
                        records.add(mapper.readValue(file.toFile(), ScenarioRecord.class));
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load scenario file: " + file, e);
                    }
                });
            records.sort(Comparator.comparing(ScenarioRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list scenarios for workspace: " + workspaceId, e);
        }
    }

    public boolean exists(String workspaceId, String scenarioId) {
        return Files.exists(scenariosDir(workspaceId).resolve(sanitize(scenarioId) + ".json"));
    }

    private Path scenariosDir(String workspaceId) {
        return workspacesRoot.resolve(sanitize(workspaceId)).resolve("scenarios");
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
