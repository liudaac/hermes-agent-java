package com.nousresearch.hermes.prompt;

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

/** File-backed repository for prompt assets. */
public class FilePromptAssetRepository {
    private final Path workspacesRoot;
    private final ObjectMapper mapper;

    public FilePromptAssetRepository(Path workspacesRoot) {
        this.workspacesRoot = workspacesRoot;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(PromptAssetRecord record) {
        try {
            Path dir = promptAssetsDir(record.getWorkspaceId());
            Files.createDirectories(dir);
            writeAtomic(dir.resolve(sanitize(record.getAssetId()) + ".json"), record);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save prompt asset: " + record.getAssetId(), e);
        }
    }

    public Optional<PromptAssetRecord> findById(String workspaceId, String assetId) {
        Path file = promptAssetsDir(workspaceId).resolve(sanitize(assetId) + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), PromptAssetRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt asset: " + workspaceId + "/" + assetId, e);
        }
    }

    public List<PromptAssetRecord> list(String workspaceId) {
        Path dir = promptAssetsDir(workspaceId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            List<PromptAssetRecord> records = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(file -> {
                    try {
                        records.add(mapper.readValue(file.toFile(), PromptAssetRecord.class));
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load prompt asset file: " + file, e);
                    }
                });
            records.sort(Comparator.comparing(PromptAssetRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list prompt assets for workspace: " + workspaceId, e);
        }
    }

    public boolean exists(String workspaceId, String assetId) {
        return Files.exists(promptAssetsDir(workspaceId).resolve(sanitize(assetId) + ".json"));
    }

    private Path promptAssetsDir(String workspaceId) {
        return workspacesRoot.resolve(sanitize(workspaceId)).resolve("prompt-assets");
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
