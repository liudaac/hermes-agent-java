package com.nousresearch.hermes.dashboard.handlers;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handler for log-related API endpoints.
 */
public class LogsHandler {
    private static final Logger logger = LoggerFactory.getLogger(LogsHandler.class);

    private final Path logsDir;

    public LogsHandler() {
        this.logsDir = Path.of(System.getProperty("user.home"), ".hermes", "logs");
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

            // If no file specified, list available log files
            if (file == null || file.isEmpty()) {
                ctx.json(getLogFiles());
                return;
            }

            // Get specific log content
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
     * Get list of available log files.
     */
    private List<java.util.Map<String, Object>> getLogFiles() {
        List<java.util.Map<String, Object>> files = new ArrayList<>();

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

        return files;
    }

    /**
     * Get content of a specific log file.
     */
    private List<String> getLogContent(String file, int maxLines, String level, String component) {
        List<String> lines = new ArrayList<>();

        try {
            Path logPath = logsDir.resolve(file);
            if (!Files.exists(logPath)) {
                // Try hermes home
                logPath = Path.of(System.getProperty("user.home"), ".hermes", file);
            }

            if (!Files.exists(logPath)) {
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
}
