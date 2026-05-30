package com.nousresearch.hermes.dashboard.handlers;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handler for log-related API endpoints.
 */
public class LogsHandler {
    private static final Logger logger = LoggerFactory.getLogger(LogsHandler.class);

    private final Path logsDir;
    private final ScheduledExecutorService tailExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "logs-tail");
            t.setDaemon(true);
            return t;
        });
    private final Set<TailSubscriber> subscribers = ConcurrentHashMap.newKeySet();

    public LogsHandler() {
        this.logsDir = Path.of(System.getProperty("user.home"), ".hermes", "logs");
        tailExecutor.scheduleAtFixedRate(this::pollSubscribers, 500, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * GET /api/logs - Get log files or specific log content
     */
    public void getLogs(Context ctx) {
        try {
            String file = ctx.queryParam("file");
            String linesStr = ctx.queryParam("lines");
            int lines = linesStr != null ? Integer.parseInt(linesStr) : 100;
            String levelParam = ctx.queryParam("level");
            String level = levelParam != null ? levelParam : "ALL";
            String componentParam = ctx.queryParam("component");
            String component = componentParam != null ? componentParam : "all";

            // If no file specified, return a stable object wrapper around available log files.
            if (file == null || file.isEmpty()) {
                ctx.json(java.util.Map.of("files", listLogFiles()));
                return;
            }

            // Get specific log content
            logger.info("getLogs request: file={}, lines={}, level={}, component={}", file, lines, level, component);
            List<String> logLines = getLogContent(file, lines, level, component);

            ctx.json(new java.util.HashMap<String, Object>() {{
                put("file", file);
                put("lines", logLines);
            }});
        } catch (Exception e) {
            logger.error("Error getting logs: {}", e.getMessage());
            ctx.status(500).result("Error getting logs");
        }
    }

    /**
     * GET /api/logs/files - Get list of available log files.
     */
    public void getLogFiles(Context ctx) {
        try {
            ctx.json(java.util.Map.of("files", listLogFiles()));
        } catch (Exception e) {
            logger.error("Error listing log files: {}", e.getMessage());
            ctx.status(500).result("Error listing logs");
        }
    }

    /**
     * DELETE /api/logs?file=xxx - Delete a log file.
     */
    public void deleteLog(Context ctx) {
        try {
            String file = ctx.queryParam("file");
            if (file == null || file.isBlank()) {
                ctx.status(400).json(java.util.Map.of("error", "Missing file parameter"));
                return;
            }

            Path logPath = resolveSafe(file);
            if (logPath == null) {
                ctx.status(404).json(java.util.Map.of("error", "File not found or access denied"));
                return;
            }

            if (!Files.isRegularFile(logPath)) {
                ctx.status(400).json(java.util.Map.of("error", "Not a regular file"));
                return;
            }

            Files.delete(logPath);
            logger.info("Deleted log file: {}", logPath);
            ctx.json(java.util.Map.of("ok", true, "file", file));
        } catch (Exception e) {
            logger.error("Error deleting log file: {}", e.getMessage());
            ctx.status(500).json(java.util.Map.of("error", e.getMessage()));
        }
    }
    private List<java.util.Map<String, Object>> listLogFiles() {
        List<java.util.Map<String, Object>> files = new ArrayList<>();
        logger.info("listLogFiles: logsDir={}, exists={}, user.home={}", logsDir, Files.exists(logsDir), System.getProperty("user.home"));

        try {
            if (Files.exists(logsDir)) {
                try (Stream<Path> stream = Files.list(logsDir)) {
                    files = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".log"))
                        .map(p -> {
                            java.util.Map<String, Object> info = new java.util.HashMap<>();
                            info.put("name", p.getFileName().toString());
                            info.put("path", p.toString());
                            try {
                                info.put("size", Files.size(p));
                                info.put("modified", Files.getLastModifiedTime(p).toMillis());
                            } catch (IOException e) {
                                info.put("size", 0);
                                info.put("modified", 0);
                            }
                            return info;
                        })
                        .collect(Collectors.toList());
                }
            }

            // Also check for agent.log in hermes home
            Path agentLog = Path.of(System.getProperty("user.home"), ".hermes", "agent.log");
            if (Files.exists(agentLog)) {
                java.util.Map<String, Object> info = new java.util.HashMap<>();
                info.put("name", "agent.log");
                info.put("path", agentLog.toString());
                try {
                    info.put("size", Files.size(agentLog));
                    info.put("modified", Files.getLastModifiedTime(agentLog).toMillis());
                } catch (IOException e) {
                    info.put("size", 0);
                    info.put("modified", 0);
                }
                files.add(info);
            }
        } catch (Exception e) {
            logger.warn("Error listing log files: {}", e.getMessage());
        }

        // Sort by modified time descending
        files.sort((a, b) -> Long.compare((Long) b.get("modified"), (Long) a.get("modified")));
        logger.info("listLogFiles returned {} files: {}", files.size(),
            files.stream().map(m -> (String) m.get("name")).collect(Collectors.toList()));

        return files;
    }

    /**
     * Get content of a specific log file.
     */
    private List<String> getLogContent(String file, int maxLines, String level, String component) {
        List<String> lines = new ArrayList<>();

        try {
            Path logPath = logsDir.resolve(file);
            logger.info("getLogContent: file={}, logsDir={}, resolved={}, exists={}",
                file, logsDir, logPath, Files.exists(logPath));

            if (!Files.exists(logPath)) {
                // Try hermes home
                logPath = Path.of(System.getProperty("user.home"), ".hermes", file);
                logger.info("getLogContent: fallback to hermes home: {}, exists={}",
                    logPath, Files.exists(logPath));
            }

            if (!Files.exists(logPath)) {
                logger.warn("Log file not found: {} (logsDir={})", file, logsDir);
                return List.of("Log file not found: " + file);
            }

            // Security check: ensure file is within allowed directories
            Path normalized = logPath.toAbsolutePath().normalize();
            Path allowedDir1 = logsDir.toAbsolutePath().normalize();
            Path allowedDir2 = Path.of(System.getProperty("user.home"), ".hermes").toAbsolutePath().normalize();

            if (!normalized.startsWith(allowedDir1) && !normalized.startsWith(allowedDir2)) {
                return List.of("Access denied");
            }

            // Read last N lines
            List<String> allLines = Files.readAllLines(logPath);

            // Filter by level if specified
            if (!level.equals("ALL")) {
                allLines = allLines.stream()
                    .filter(l -> l.contains(level) || matchesLogLevel(l, level))
                    .collect(Collectors.toList());
            }

            // Filter by component if specified
            if (!component.equals("all")) {
                allLines = allLines.stream()
                    .filter(l -> l.toLowerCase().contains(component.toLowerCase()))
                    .collect(Collectors.toList());
            }

            // Get last N lines
            int start = Math.max(0, allLines.size() - maxLines);
            lines = allLines.subList(start, allLines.size());

        } catch (Exception e) {
            logger.error("Error reading log file: {}", e.getMessage());
            return List.of("Error reading log: " + e.getMessage());
        }

        return lines;
    }

    private boolean matchesLogLevel(String line, String level) {
        // Check for common log level patterns
        String upperLine = line.toUpperCase();
        String upperLevel = level.toUpperCase();

        return switch (upperLevel) {
            case "DEBUG" -> upperLine.contains("DEBUG") || upperLine.contains("[D]");
            case "INFO" -> upperLine.contains("INFO") || upperLine.contains("[I]") ||
                           (!upperLine.contains("DEBUG") && !upperLine.contains("WARN") &&
                            !upperLine.contains("ERROR"));
            case "WARNING" -> upperLine.contains("WARN") || upperLine.contains("[W]");
            case "ERROR" -> upperLine.contains("ERROR") || upperLine.contains("[E]");
            default -> true;
        };
    }

    /* ----------------------------------------------------------------- */
    /* Multi-file aggregation                                            */
    /* ----------------------------------------------------------------- */

    /**
     * GET /api/logs/aggregate?files=a.log,b.log&lines=200&level=ALL
     * Returns recent lines from multiple files merged + sorted by best-effort
     * lexicographic timestamp prefix (works for slf4j default pattern).
     */
    public void getAggregate(Context ctx) {
        try {
            String filesParam = ctx.queryParam("files");
            String levelParam = ctx.queryParam("level");
            String componentParam = ctx.queryParam("component");
            String linesStr = ctx.queryParam("lines");
            int perFileLines = linesStr != null ? Integer.parseInt(linesStr) : 200;
            String level = levelParam != null ? levelParam : "ALL";
            String component = componentParam != null ? componentParam : "all";

            List<String> files;
            if (filesParam == null || filesParam.isBlank()) {
                files = listLogFiles().stream()
                    .map(m -> (String) m.get("name"))
                    .limit(5)
                    .collect(Collectors.toList());
            } else {
                files = List.of(filesParam.split(","));
            }

            List<java.util.Map<String, Object>> merged = new ArrayList<>();
            for (String f : files) {
                String name = f.trim();
                if (name.isEmpty()) continue;
                List<String> lines = getLogContent(name, perFileLines, level, component);
                for (String line : lines) {
                    java.util.Map<String, Object> entry = new java.util.HashMap<>();
                    entry.put("file", name);
                    entry.put("line", line);
                    merged.add(entry);
                }
            }
            // Sort by leading timestamp (best-effort); lines without prefix keep insertion order.
            merged.sort((a, b) -> {
                String la = (String) a.get("line");
                String lb = (String) b.get("line");
                String pa = la.length() > 23 ? la.substring(0, 23) : la;
                String pb = lb.length() > 23 ? lb.substring(0, 23) : lb;
                return pa.compareTo(pb);
            });
            // Cap response size to avoid blowing up the dashboard.
            int max = Math.min(merged.size(), perFileLines * 4);
            int start = Math.max(0, merged.size() - max);
            ctx.json(java.util.Map.of(
                "files", files,
                "count", merged.size(),
                "entries", merged.subList(start, merged.size())
            ));
        } catch (Exception e) {
            logger.error("Error aggregating logs: {}", e.getMessage(), e);
            ctx.status(500).result("Error aggregating logs");
        }
    }

    /* ----------------------------------------------------------------- */
    /* SSE tail                                                          */
    /* ----------------------------------------------------------------- */

    /**
     * GET /api/logs/tail?file=agent.log[&level=ALL][&component=all]
     * SSE stream: emits "line" events with {file, line} JSON payloads
     * whenever the file grows.
     */
    public void tail(SseClient client) {
        String file = client.ctx().queryParam("file");
        if (file == null || file.isBlank()) {
            client.sendEvent("error", "{\"message\":\"missing file parameter\"}");
            client.close();
            return;
        }
        Path resolved = resolveSafe(file);
        if (resolved == null) {
            client.sendEvent("error", "{\"message\":\"file not found or access denied\"}");
            client.close();
            return;
        }
        String levelParam = client.ctx().queryParam("level");
        String componentParam = client.ctx().queryParam("component");

        long initial;
        try {
            initial = Files.size(resolved);
        } catch (IOException e) {
            initial = 0;
        }
        TailSubscriber sub = new TailSubscriber(
            client,
            file,
            resolved,
            initial,
            levelParam != null ? levelParam : "ALL",
            componentParam != null ? componentParam : "all"
        );
        client.keepAlive();
        client.onClose(() -> subscribers.remove(sub));
        subscribers.add(sub);
        client.sendEvent("ready", "{\"file\":\"" + escape(file) + "\",\"offset\":" + initial + "}");
    }

    private void pollSubscribers() {
        if (subscribers.isEmpty()) return;
        for (TailSubscriber sub : new HashSet<>(subscribers)) {
            try {
                if (!Files.exists(sub.path)) continue;
                long size = Files.size(sub.path);
                if (size < sub.offset) {
                    // truncated/rotated — reset to start
                    sub.offset = 0;
                }
                if (size == sub.offset) continue;
                try (RandomAccessFile raf = new RandomAccessFile(sub.path.toFile(), "r")) {
                    raf.seek(sub.offset);
                    String line;
                    while ((line = raf.readLine() ) != null) {
                        // RandomAccessFile.readLine returns ISO-8859-1; re-encode.
                        String fixed = new String(line.getBytes("ISO-8859-1"), java.nio.charset.StandardCharsets.UTF_8);
                        if (!matchesFilters(fixed, sub.level, sub.component)) continue;
                        String payload = "{\"file\":\"" + escape(sub.file)
                            + "\",\"line\":\"" + escape(fixed) + "\"}";
                        try {
                            sub.client.sendEvent("line", payload);
                        } catch (Exception e) {
                            // client likely closed
                            subscribers.remove(sub);
                            break;
                        }
                    }
                    sub.offset = raf.getFilePointer();
                }
            } catch (Exception e) {
                logger.debug("tail poll error for {}: {}", sub.file, e.getMessage());
            }
        }
    }

    private boolean matchesFilters(String line, String level, String component) {
        if (!"ALL".equalsIgnoreCase(level)
            && !line.contains(level)
            && !matchesLogLevel(line, level)) {
            return false;
        }
        if (!"all".equalsIgnoreCase(component)
            && !line.toLowerCase().contains(component.toLowerCase())) {
            return false;
        }
        return true;
    }

    private Path resolveSafe(String file) {
        Path logPath = logsDir.resolve(file);
        if (!Files.exists(logPath)) {
            logPath = Path.of(System.getProperty("user.home"), ".hermes", file);
        }
        if (!Files.exists(logPath)) return null;
        Path normalized = logPath.toAbsolutePath().normalize();
        Path allowed1 = logsDir.toAbsolutePath().normalize();
        Path allowed2 = Path.of(System.getProperty("user.home"), ".hermes").toAbsolutePath().normalize();
        if (!normalized.startsWith(allowed1) && !normalized.startsWith(allowed2)) {
            return null;
        }
        return normalized;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    int subscriberCount() {
        return subscribers.size();
    }

    public void shutdown() {
        tailExecutor.shutdownNow();
    }

    private static final class TailSubscriber {
        final SseClient client;
        final String file;
        final Path path;
        volatile long offset;
        final String level;
        final String component;

        TailSubscriber(SseClient client, String file, Path path, long offset, String level, String component) {
            this.client = client;
            this.file = file;
            this.path = path;
            this.offset = offset;
            this.level = level;
            this.component = component;
        }
    }
}
