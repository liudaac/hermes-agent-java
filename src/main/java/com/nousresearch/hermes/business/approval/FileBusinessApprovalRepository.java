package com.nousresearch.hermes.business.approval;

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

/** File-backed repository for Business Portal approval cards. */
public class FileBusinessApprovalRepository {
    private final Path workspacesRoot;
    private final ObjectMapper mapper;

    public FileBusinessApprovalRepository(Path workspacesRoot) {
        this.workspacesRoot = workspacesRoot;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(BusinessApprovalRecord record) {
        try {
            Path dir = approvalsDir(record.getWorkspaceId());
            Files.createDirectories(dir);
            writeAtomic(dir.resolve(sanitize(record.getApprovalId()) + ".json"), record);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save business approval: " + record.getApprovalId(), e);
        }
    }

    public Optional<BusinessApprovalRecord> findById(String workspaceId, String approvalId) {
        Path file = approvalsDir(workspaceId).resolve(sanitize(approvalId) + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), BusinessApprovalRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load business approval: " + workspaceId + "/" + approvalId, e);
        }
    }

    public List<BusinessApprovalRecord> list(String workspaceId, String status) {
        Path dir = approvalsDir(workspaceId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            List<BusinessApprovalRecord> records = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(file -> {
                    try {
                        BusinessApprovalRecord record = mapper.readValue(file.toFile(), BusinessApprovalRecord.class);
                        if (status == null || status.isBlank() || status.equalsIgnoreCase(record.getStatus())) {
                            records.add(record);
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load business approval file: " + file, e);
                    }
                });
            records.sort(Comparator.comparing(BusinessApprovalRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list business approvals for workspace: " + workspaceId, e);
        }
    }

    public List<BusinessApprovalRecord> listAll(List<String> workspaceIds, String status) {
        List<BusinessApprovalRecord> all = new ArrayList<>();
        for (String workspaceId : workspaceIds) {
            all.addAll(list(workspaceId, status));
        }
        all.sort(Comparator.comparing(BusinessApprovalRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return all;
    }

    private Path approvalsDir(String workspaceId) {
        return workspacesRoot.resolve(sanitize(workspaceId)).resolve("approvals");
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
