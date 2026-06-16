package com.nousresearch.hermes.business.run;

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

/** File-backed repository for Business Portal run stories. */
public class FileBusinessRunRepository {
    private final Path workspacesRoot;
    private final ObjectMapper mapper;

    public FileBusinessRunRepository(Path workspacesRoot) {
        this.workspacesRoot = workspacesRoot;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(BusinessRunRecord record) {
        try {
            Path dir = runsDir(record.getWorkspaceId());
            Files.createDirectories(dir);
            writeAtomic(dir.resolve(sanitize(record.getRunId()) + ".json"), record);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save business run: " + record.getRunId(), e);
        }
    }

    public Optional<BusinessRunRecord> findById(String workspaceId, String runId) {
        Path file = runsDir(workspaceId).resolve(sanitize(runId) + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), BusinessRunRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load business run: " + workspaceId + "/" + runId, e);
        }
    }

    public List<BusinessRunRecord> list(String workspaceId, String teamId, String status, int limit) {
        Path dir = runsDir(workspaceId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            List<BusinessRunRecord> records = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(file -> {
                    try {
                        BusinessRunRecord record = mapper.readValue(file.toFile(), BusinessRunRecord.class);
                        if (matches(record, teamId, status)) {
                            records.add(record);
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load business run file: " + file, e);
                    }
                });
            records.sort(Comparator.comparing(BusinessRunRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
            int safeLimit = limit <= 0 ? records.size() : Math.min(limit, records.size());
            return records.subList(0, safeLimit);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list business runs for workspace: " + workspaceId, e);
        }
    }

    public List<BusinessRunRecord> listAll(List<String> workspaceIds, String teamId, String status, int limit) {
        List<BusinessRunRecord> all = new ArrayList<>();
        for (String workspaceId : workspaceIds) {
            all.addAll(list(workspaceId, teamId, status, 0));
        }
        all.sort(Comparator.comparing(BusinessRunRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        int safeLimit = limit <= 0 ? all.size() : Math.min(limit, all.size());
        return all.subList(0, safeLimit);
    }

    private boolean matches(BusinessRunRecord record, String teamId, String status) {
        boolean teamOk = teamId == null || teamId.isBlank() || teamId.equals(record.getTeamId());
        boolean statusOk = status == null || status.isBlank() || status.equalsIgnoreCase(record.getStatus());
        return teamOk && statusOk;
    }

    private Path runsDir(String workspaceId) {
        return workspacesRoot.resolve(sanitize(workspaceId)).resolve("runs");
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
