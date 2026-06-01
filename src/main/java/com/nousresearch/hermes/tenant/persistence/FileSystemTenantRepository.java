package com.nousresearch.hermes.tenant.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nousresearch.hermes.utils.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileSystemTenantRepository implements TenantStateRepository {
    private static final Logger log = LoggerFactory.getLogger(FileSystemTenantRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    
    private final Path baseDir;
    private final RetryPolicy retryPolicy;
    private final ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "fs-repo");
        t.setDaemon(true);
        return t;
    });

    public FileSystemTenantRepository(Path dir) {
        this(dir, RetryPolicy.defaults());
    }

    public FileSystemTenantRepository(Path dir, RetryPolicy retryPolicy) {
        this.baseDir = dir.toAbsolutePath().normalize();
        this.retryPolicy = retryPolicy;
        try {
            Files.createDirectories(baseDir.resolve("tenants"));
            Files.createDirectories(baseDir.resolve("sessions"));
        } catch (IOException e) {
            log.error("Init error", e);
        }
    }

    public CompletableFuture<Void> saveState(String tid, TenantStateSnapshot s) {
        return CompletableFuture.runAsync(() -> {
            retryPolicy.executeVoid("saveState-" + tid, () -> {
                try {
                    Path p = baseDir.resolve("tenants").resolve(tid);
                    Files.createDirectories(p);
                    writeJsonAtomically(p.resolve("state.json"), s);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }, exec);
    }

    public CompletableFuture<Optional<TenantStateSnapshot>> loadState(String tid) {
        return CompletableFuture.supplyAsync(() ->
            retryPolicy.execute("loadState-" + tid, () -> {
                try {
                    Path p = baseDir.resolve("tenants").resolve(tid).resolve("state.json");
                    return readJsonWithBackup(p, TenantStateSnapshot.class);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }), exec);
    }

    public CompletableFuture<Void> deleteState(String tid) {
        return CompletableFuture.runAsync(() -> {
            try {
                delTree(baseDir.resolve("tenants").resolve(tid));
            } catch (Exception e) {
                log.error("delState {}", tid, e);
            }
        }, exec);
    }

    public CompletableFuture<List<String>> listTenants() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> l = new ArrayList<>();
            try (var s = Files.list(baseDir.resolve("tenants"))) {
                s.filter(Files::isDirectory)
                 .map(p -> p.getFileName().toString())
                 .forEach(l::add);
            } catch (Exception e) {
            }
            return l;
        }, exec);
    }

    public CompletableFuture<Boolean> exists(String tid) {
        return CompletableFuture.supplyAsync(() -> {
            Path state = baseDir.resolve("tenants").resolve(tid).resolve("state.json");
            return Files.exists(state) || Files.exists(backupPath(state));
        }, exec);
    }

    public CompletableFuture<Optional<Instant>> getLastUpdated(String tid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path p = baseDir.resolve("tenants").resolve(tid).resolve("state.json");
                if (Files.exists(p)) {
                    return Optional.of(Files.getLastModifiedTime(p).toInstant());
                }
                Path backup = backupPath(p);
                if (Files.exists(backup)) {
                    return Optional.of(Files.getLastModifiedTime(backup).toInstant());
                }
                return Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        }, exec);
    }

    public CompletableFuture<Void> saveSession(String tid, String sid, SessionState s) {
        return CompletableFuture.runAsync(() -> {
            retryPolicy.executeVoid("saveSession-" + tid + "-" + sid, () -> {
                try {
                    Path p = baseDir.resolve("sessions").resolve(tid);
                    Files.createDirectories(p);
                    writeJsonAtomically(p.resolve(sid + ".json"), s);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }, exec);
    }

    public CompletableFuture<Optional<SessionState>> loadSession(String tid, String sid) {
        return CompletableFuture.supplyAsync(() ->
            retryPolicy.execute("loadSession-" + tid + "-" + sid, () -> {
                try {
                    Path p = baseDir.resolve("sessions").resolve(tid).resolve(sid + ".json");
                    return readJsonWithBackup(p, SessionState.class);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }), exec);
    }

    public CompletableFuture<List<String>> listSessions(String tid) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> l = new ArrayList<>();
            try {
                Path d = baseDir.resolve("sessions").resolve(tid);
                if (Files.exists(d)) {
                    try (var s = Files.list(d)) {
                        s.filter(p -> p.toString().endsWith(".json"))
                         .map(p -> p.getFileName().toString().replace(".json", ""))
                         .forEach(l::add);
                    }
                }
            } catch (Exception e) {
            }
            return l;
        }, exec);
    }

    public CompletableFuture<Void> deleteSession(String tid, String sid) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(baseDir.resolve("sessions").resolve(tid).resolve(sid + ".json"));
            } catch (Exception e) {
            }
        }, exec);
    }

    public void close() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Write JSON through a temporary file and then atomically move it into place.
     * This prevents corrupted partial JSON files when the process is interrupted
     * during persistence. The previous good version is kept as a .bak file.
     */
    private void writeJsonAtomically(Path target, Object value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Path backup = backupPath(target);
        try {
            MAPPER.writeValue(tmp.toFile(), value);

            // Preserve the last known-good version before replacing it.
            if (Files.exists(target)) {
                try {
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    log.warn("Failed to create backup for {}: {}", target, e.getMessage());
                }
            }

            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Read JSON from the primary file and fall back to .bak if the primary file
     * is missing or corrupted.
     */
    private <T> Optional<T> readJsonWithBackup(Path target, Class<T> type) throws IOException {
        IOException primaryError = null;

        if (Files.exists(target)) {
            try {
                return Optional.of(MAPPER.readValue(target.toFile(), type));
            } catch (IOException e) {
                primaryError = e;
                log.warn("Failed to read primary JSON {}, trying backup: {}", target, e.getMessage());
            }
        }

        Path backup = backupPath(target);
        if (Files.exists(backup)) {
            try {
                T recovered = MAPPER.readValue(backup.toFile(), type);
                log.warn("Recovered JSON from backup: {}", backup);
                return Optional.of(recovered);
            } catch (IOException backupError) {
                if (primaryError != null) {
                    backupError.addSuppressed(primaryError);
                }
                throw backupError;
            }
        }

        if (primaryError != null) {
            throw primaryError;
        }
        return Optional.empty();
    }

    private Path backupPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }

    private void delTree(Path p) throws IOException {
        if (Files.exists(p)) {
            try (var s = Files.walk(p)) {
                s.sorted((a, b) -> b.compareTo(a))
                 .forEach(x -> {
                     try {
                         Files.delete(x);
                     } catch (IOException e) {
                     }
                 });
            }
        }
    }
}
