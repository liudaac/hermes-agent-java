package com.nousresearch.hermes.evolution;

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

/** File-backed repository for evolution proposals. */
public class FileEvolutionProposalRepository {
    private final Path workspacesRoot;
    private final ObjectMapper mapper;

    public FileEvolutionProposalRepository(Path workspacesRoot) {
        this.workspacesRoot = workspacesRoot;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(EvolutionProposalRecord record) {
        try {
            Path dir = proposalsDir(record.getWorkspaceId());
            Files.createDirectories(dir);
            writeAtomic(dir.resolve(sanitize(record.getProposalId()) + ".json"), record);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save evolution proposal: " + record.getProposalId(), e);
        }
    }

    public Optional<EvolutionProposalRecord> findById(String workspaceId, String proposalId) {
        Path file = proposalsDir(workspaceId).resolve(sanitize(proposalId) + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), EvolutionProposalRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load evolution proposal: " + workspaceId + "/" + proposalId, e);
        }
    }

    public List<EvolutionProposalRecord> list(String workspaceId, String status) {
        Path dir = proposalsDir(workspaceId);
        if (!Files.exists(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            List<EvolutionProposalRecord> records = new ArrayList<>();
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(file -> {
                    try {
                        EvolutionProposalRecord record = mapper.readValue(file.toFile(), EvolutionProposalRecord.class);
                        if (status == null || status.isBlank() || status.equalsIgnoreCase(record.getStatus())) records.add(record);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load evolution proposal file: " + file, e);
                    }
                });
            records.sort(Comparator.comparing(EvolutionProposalRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list evolution proposals for workspace: " + workspaceId, e);
        }
    }

    private Path proposalsDir(String workspaceId) {
        return workspacesRoot.resolve(sanitize(workspaceId)).resolve("evolution-proposals");
    }

    private void writeAtomic(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), value);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String sanitize(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
