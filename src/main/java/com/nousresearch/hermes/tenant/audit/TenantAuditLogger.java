package com.nousresearch.hermes.tenant.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 租户审计日志器
 */
public class TenantAuditLogger {
    private static final Logger logger = LoggerFactory.getLogger(TenantAuditLogger.class);
    
    private final Path logsDir;
    private final Path auditLogFile;
    private final BlockingQueue<AuditEntry> logQueue;
    
    public TenantAuditLogger(Path logsDir) {
        this.logsDir = logsDir;
        this.auditLogFile = logsDir.resolve("audit.log");
        this.logQueue = new LinkedBlockingQueue<>();
        
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            logger.error("Failed to create logs directory", e);
        }
        
        // 启动异步日志线程
        startLogWriter();
    }
    
    /**
     * 记录审计事件
     */
    public void log(AuditEvent event, Map<String, Object> details) {
        AuditEntry entry = new AuditEntry(
            Instant.now(),
            event,
            details
        );
        
        logQueue.offer(entry);
        
        // 同时输出到 SLF4J
        logger.info("[AUDIT] {}: {}", event, details);
    }
    
    private void startLogWriter() {
        Thread writer = new Thread(this::writeLoop, "audit-writer");
        writer.setDaemon(true);
        writer.start();
    }
    
    private void writeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                AuditEntry entry = logQueue.take();
                writeEntry(entry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    private void writeEntry(AuditEntry entry) {
        try {
            String line = String.format("%s [%s] %s%n",
                DateTimeFormatter.ISO_INSTANT.format(entry.timestamp()),
                entry.event(),
                entry.details()
            );
            
            Files.writeString(auditLogFile, line, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.APPEND);
            
        } catch (IOException e) {
            logger.error("Failed to write audit log", e);
        }
    }
    
    /**
     * 获取最近的审计事件
     */
    public java.util.List<AuditEntry> getRecentEvents(int limit) {
        java.util.List<AuditEntry> events = new java.util.ArrayList<>();
        
        if (!Files.exists(auditLogFile)) {
            return events;
        }
        
        try {
            java.util.List<String> lines = Files.readAllLines(auditLogFile);
            // 从后往前读取，最多 limit 条
            int start = Math.max(0, lines.size() - limit);
            for (int i = lines.size() - 1; i >= start && events.size() < limit; i--) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                
                // 简单解析日志行
                try {
                    int bracket1 = line.indexOf('[');
                    int bracket2 = line.indexOf(']', bracket1);
                    
                    if (bracket1 > 0 && bracket2 > bracket1) {
                        String timestampStr = line.substring(0, bracket1).trim();
                        String eventStr = line.substring(bracket1 + 1, bracket2).trim();
                        String detailsStr = line.substring(bracket2 + 1).trim();
                        
                        Instant timestamp = Instant.parse(timestampStr);
                        AuditEvent event = AuditEvent.valueOf(eventStr);
                        Map<String, Object> details = new java.util.HashMap<>();
                        details.put("raw", detailsStr);
                        
                        events.add(new AuditEntry(timestamp, event, details));
                    }
                } catch (Exception e) {
                    // 解析失败，跳过
                }
            }
        } catch (IOException e) {
            logger.error("Failed to read audit log", e);
        }
        
        return events;
    }
    
    // ============ 记录类 ============
    
    public record AuditEntry(Instant timestamp, AuditEvent event, Map<String, Object> details) {}
}
