package com.nousresearch.hermes.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Memory management for persistent cross-session memory.
 * Stores durable facts, user preferences, and learned patterns.
 */
public class MemoryManager {
    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    
    private final Path memoryDir;
    private final Path memoryFile;
    private final Path sessionDir;
    
    public MemoryManager() {
        this.memoryDir = Constants.getHermesHome().resolve("memory");
        this.memoryFile = memoryDir.resolve("memory.json");
        this.sessionDir = memoryDir.resolve("sessions");
        
        try {
            Files.createDirectories(memoryDir);
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            logger.error("Failed to create memory directories: {}", e.getMessage());
        }
    }
    
    /**
     * Save a memory entry.
     */
    public void save(String category, String content, Map<String, Object> metadata) {
        try {
            List<MemoryEntry> memories = loadMemories();
            
            MemoryEntry entry = new MemoryEntry();
            entry.id = UUID.randomUUID().toString();
            entry.timestamp = Instant.now();
            entry.category = category;
            entry.content = content;
            entry.metadata = metadata != null ? metadata : new HashMap<>();
            
            // Check for similar existing memories
            memories.removeIf(m -> isSimilar(m, entry));
            memories.add(entry);
            
            // Keep only recent memories per category
            memories = pruneMemories(memories);
            
            saveMemories(memories);
            logger.debug("Saved memory: {} - {}", category, content.substring(0, Math.min(50, content.length())));
            
        } catch (Exception e) {
            logger.error("Failed to save memory: {}", e.getMessage());
        }
    }
    
    /**
     * Search memories by query.
     */
    public List<MemoryEntry> search(String query, int limit) {
        try {
            List<MemoryEntry> memories = loadMemories();
            String lowerQuery = query.toLowerCase();
            
            return memories.stream()
                .filter(m -> m.content.toLowerCase().contains(lowerQuery) ||
                            m.category.toLowerCase().contains(lowerQuery))
                .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
                .limit(limit)
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            logger.error("Failed to search memories: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Get memories by category.
     */
    public List<MemoryEntry> getByCategory(String category, int limit) {
        try {
            List<MemoryEntry> memories = loadMemories();
            
            return memories.stream()
                .filter(m -> m.category.equals(category))
                .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
                .limit(limit)
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            logger.error("Failed to get memories: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Delete a memory by ID.
     */
    public boolean delete(String id) {
        try {
            List<MemoryEntry> memories = loadMemories();
            boolean removed = memories.removeIf(m -> m.id.equals(id));
            if (removed) {
                saveMemories(memories);
                logger.debug("Deleted memory: {}", id);
            }
            return removed;
        } catch (Exception e) {
            logger.error("Failed to delete memory: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Build memory context block for system prompt.
     */
    public String buildMemoryContext() {
        try {
            List<MemoryEntry> memories = loadMemories();
            if (memories.isEmpty()) {
                return "";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("## Persistent Memory\n\n");
            
            // Group by category
            Map<String, List<MemoryEntry>> byCategory = memories.stream()
                .collect(Collectors.groupingBy(m -> m.category));
            
            for (Map.Entry<String, List<MemoryEntry>> entry : byCategory.entrySet()) {
                sb.append("### ").append(entry.getKey()).append("\n");
                entry.getValue().stream()
                    .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
                    .limit(5)
                    .forEach(m -> sb.append("- ").append(m.content).append("\n"));
                sb.append("\n");
            }
            
            return sb.toString();
            
        } catch (Exception e) {
            logger.error("Failed to build memory context: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * Save session transcript.
     */
    public void saveSession(String sessionId, List<Map<String, String>> messages) {
        try {
            Path sessionFile = sessionDir.resolve(sessionId + ".json");
            mapper.writeValue(sessionFile.toFile(), messages);
            logger.debug("Saved session: {}", sessionId);
        } catch (Exception e) {
            logger.error("Failed to save session: {}", e.getMessage());
        }
    }
    
    /**
     * Search session history.
     */
    public List<SessionSearchResult> searchSessions(String query, int limit) {
        List<SessionSearchResult> results = new ArrayList<>();
        
        try {
            if (!Files.exists(sessionDir)) {
                return results;
            }
            
            String lowerQuery = query.toLowerCase();
            
            Files.list(sessionDir)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, String>> messages = mapper.readValue(path.toFile(), List.class);
                        
                        for (int i = 0; i < messages.size(); i++) {
                            Map<String, String> msg = messages.get(i);
                            String content = msg.getOrDefault("content", "");
                            
                            if (content.toLowerCase().contains(lowerQuery)) {
                                SessionSearchResult result = new SessionSearchResult();
                                result.sessionId = path.getFileName().toString().replace(".json", "");
                                result.messageIndex = i;
                                result.role = msg.getOrDefault("role", "unknown");
                                result.content = content;
                                result.timestamp = Files.getLastModifiedTime(path).toInstant();
                                results.add(result);
                                
                                if (results.size() >= limit) {
                                    return;
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to read session file: {}", path);
                    }
                });
                
        } catch (Exception e) {
            logger.error("Failed to search sessions: {}", e.getMessage());
        }
        
        return results;
    }
    
    // Helper methods
    private List<MemoryEntry> loadMemories() {
        try {
            if (!Files.exists(memoryFile)) {
                return new ArrayList<>();
            }
            String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                return new ArrayList<>();
            }
            @SuppressWarnings("unchecked")
            List<MemoryEntry> memories = mapper.readValue(content, List.class);
            return memories != null ? memories : new ArrayList<>();
        } catch (Exception e) {
            logger.error("Failed to load memories: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    private void saveMemories(List<MemoryEntry> memories) {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                .writeValue(memoryFile.toFile(), memories);
        } catch (Exception e) {
            logger.error("Failed to save memories: {}", e.getMessage());
        }
    }
    
    private boolean isSimilar(MemoryEntry a, MemoryEntry b) {
        // Simple similarity check - same category and similar content
        if (!a.category.equals(b.category)) {
            return false;
        }
        // Check if content is very similar (simplified)
        return a.content.equalsIgnoreCase(b.content) ||
               (a.content.length() > 20 && b.content.length() > 20 &&
                a.content.substring(0, 20).equalsIgnoreCase(b.content.substring(0, 20)));
    }
    
    private List<MemoryEntry> pruneMemories(List<MemoryEntry> memories) {
        // Keep max 100 memories per category
        Map<String, List<MemoryEntry>> byCategory = memories.stream()
            .collect(Collectors.groupingBy(m -> m.category));
        
        List<MemoryEntry> pruned = new ArrayList<>();
        for (List<MemoryEntry> categoryMemories : byCategory.values()) {
            categoryMemories.stream()
                .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
                .limit(100)
                .forEach(pruned::add);
        }
        
        return pruned;
    }
    
    // Data classes
    public static class MemoryEntry {
        public String id;
        public Instant timestamp;
        public String category;
        public String content;
        public Map<String, Object> metadata;
    }
    
    public static class SessionSearchResult {
        public String sessionId;
        public int messageIndex;
        public String role;
        public String content;
        public Instant timestamp;
    }
}