package com.nousresearch.hermes.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * S2-3: MOA trace JSONL writer — 多 agent 共享 trace 文件。
 *
 * <p>每租户每天一个文件：{@code traces/<tenantId>/<yyyy-MM-dd>.jsonl}</p>
 *
 * <p>开关：{@code agent.trace.jsonl.enabled=true}（默认 false）</p>
 *
 * <p>线程安全：每个文件路径一把锁，多 agent 并发写不交错。</p>
 */
public class MoaTraceWriter {
    private static final Logger logger = LoggerFactory.getLogger(MoaTraceWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path baseDir;
    private final boolean enabled;
    private final ConcurrentHashMap<Path, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    public MoaTraceWriter(Path baseDir, boolean enabled) {
        this.baseDir = baseDir;
        this.enabled = enabled;
    }

    /**
     * 从系统属性创建（{@code agent.trace.jsonl.enabled} + {@code agent.trace.jsonl.dir}）。
     */
    public static MoaTraceWriter fromSystemProperties() {
        boolean enabled = Boolean.parseBoolean(
            System.getProperty("agent.trace.jsonl.enabled", "false"));
        String dir = System.getProperty("agent.trace.jsonl.dir", "traces");
        return new MoaTraceWriter(Path.of(dir), enabled);
    }

    /**
     * 写一条 trace 记录。
     *
     * @param tenantId 租户 ID
     * @param record trace 记录
     */
    public void write(String tenantId, MoaTraceRecord record) {
        if (!enabled || record == null) return;

        try {
            Path file = resolveTraceFile(tenantId);
            String json = MAPPER.writeValueAsString(record);
            writeLine(file, json);
        } catch (Exception e) {
            logger.warn("Failed to write MOA trace: {}", e.getMessage());
        }
    }

    /**
     * 解析 trace 文件路径：traces/<tenantId>/<yyyy-MM-dd>.jsonl
     */
    Path resolveTraceFile(String tenantId) {
        String date = LocalDate.now().toString();
        return baseDir.resolve(tenantId).resolve(date + ".jsonl");
    }

    /**
     * 线程安全地写入一行 JSONL。
     */
    private void writeLine(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        ReentrantLock lock = fileLocks.computeIfAbsent(file, k -> new ReentrantLock());
        lock.lock();
        try {
            Files.writeString(file, json + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } finally {
            lock.unlock();
        }
    }

    public boolean isEnabled() { return enabled; }

    public Path getBaseDir() { return baseDir; }
}
