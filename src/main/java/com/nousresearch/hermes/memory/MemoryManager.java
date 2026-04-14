package com.nousresearch.hermes.memory;

import com.nousresearch.hermes.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Memory management for persistent cross-session memory.
 * Aligned with Python Hermes: stores MEMORY.md and USER.md in ~/.hermes/memories/
 * 
 * Design:
 * - Two parallel states:
 *   - _systemPromptSnapshot: frozen at load time, used for system prompt injection
 *   - memoryEntries / userEntries: live state, mutated by tool calls, persisted to disk
 * - Entry delimiter: § (section sign)
 * - Mid-session writes update files immediately but do NOT change system prompt
 *   (preserves prefix cache for the entire session)
 */
public class MemoryManager {
    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);
    
    // Entry delimiter - same as Python version
    private static final String ENTRY_DELIMITER = "\n§\n";
    
    // Character limits (not tokens) - same as Python version
    private static final int MEMORY_CHAR_LIMIT = 2200;
    private static final int USER_CHAR_LIMIT = 1375;
    
    // Memory directory
    private final Path memoriesDir;
    private final Path memoryFile;
    private final Path userFile;
    
    // Lock for thread-safe file operations
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Live state - mutated by tool calls
    private List<String> memoryEntries = new ArrayList<>();
    private List<String> userEntries = new ArrayList<>();
    
    // Frozen snapshot for system prompt - set once at load time
    private final Map<String, String> systemPromptSnapshot = new HashMap<>();
    
    // Security patterns for injection/exfiltration detection
    private static final Pattern[] THREAT_PATTERNS = {
        Pattern.compile("ignore\\s+(previous|all|above|prior)\\s+instructions", Pattern.CASE_INSENSITIVE),
        Pattern.compile("you\\s+are\\s+now\\s+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("do\\s+not\\s+tell\\s+the\\s+user", Pattern.CASE_INSENSITIVE),
        Pattern.compile("system\\s+prompt\\s+override", Pattern.CASE_INSENSITIVE),
        Pattern.compile("disregard\\s+(your|all|any)\\s+(instructions|rules|guidelines)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("curl\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("wget\\s+[^\\n]*\\$\\{?\\w*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|API)", Pattern.CASE_INSENSITIVE),
    };
    
    private static final Set<Character> INVISIBLE_CHARS = Set.of(
        '\u200b', '\u200c', '\u200d', '\u2060', '\ufeff',
        '\u202a', '\u202b', '\u202c', '\u202d', '\u202e'
    );
    
    public MemoryManager() {
        this.memoriesDir = Constants.getHermesHome().resolve("memories");
        this.memoryFile = memoriesDir.resolve("MEMORY.md");
        this.userFile = memoriesDir.resolve("USER.md");
        
        try {
            Files.createDirectories(memoriesDir);
        } catch (IOException e) {
            logger.error("Failed to create memories directory: {}", e.getMessage());
        }
        
        // Load from disk and capture system prompt snapshot
        loadFromDisk();
    }
    
    /**
     * Load entries from MEMORY.md and USER.md, capture system prompt snapshot.
     */
    public void loadFromDisk() {
        lock.writeLock().lock();
        try {
            memoryEntries = readFile(memoryFile);
            userEntries = readFile(userFile);
            
            // Deduplicate entries (preserves order, keeps first occurrence)
            memoryEntries = deduplicate(memoryEntries);
            userEntries = deduplicate(userEntries);
            
            // Capture frozen snapshot for system prompt injection
            systemPromptSnapshot.put("memory", renderBlock("memory", memoryEntries));
            systemPromptSnapshot.put("user", renderBlock("user", userEntries));
            
            logger.info("Loaded {} memory entries, {} user entries", 
                memoryEntries.size(), userEntries.size());
                
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get the frozen system prompt snapshot.
     * This never changes mid-session to preserve prefix cache.
     */
    public String getSystemPromptSnapshot() {
        StringBuilder sb = new StringBuilder();
        
        String memoryBlock = systemPromptSnapshot.get("memory");
        if (memoryBlock != null && !memoryBlock.isEmpty()) {
            sb.append(memoryBlock);
        }
        
        String userBlock = systemPromptSnapshot.get("user");
        if (userBlock != null && !userBlock.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(userBlock);
        }
        
        return sb.toString();
    }
    
    /**
     * Add a memory entry.
     */
    public boolean addMemory(String content) {
        return addEntry(content, "memory", MEMORY_CHAR_LIMIT);
    }
    
    /**
     * Add a user entry.
     */
    public boolean addUser(String content) {
        return addEntry(content, "user", USER_CHAR_LIMIT);
    }
    
    /**
     * Search memories by query.
     */
    public List<String> search(String query, int limit) {
        lock.readLock().lock();
        try {
            String lowerQuery = query.toLowerCase();
            
            List<String> results = new ArrayList<>();
            results.addAll(searchInEntries(memoryEntries, lowerQuery));
            results.addAll(searchInEntries(userEntries, lowerQuery));
            
            return results.stream()
                .limit(limit)
                .collect(Collectors.toList());
                
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get memories by category.
     */
    public List<String> getByCategory(String category, int limit) {
        lock.readLock().lock();
        try {
            List<String> entries = "user".equals(category) ? userEntries : memoryEntries;
            return entries.stream()
                .limit(limit)
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Delete a memory entry by substring match.
     */
    public boolean delete(String category, String substring) {
        lock.writeLock().lock();
        try {
            List<String> entries = "user".equals(category) ? userEntries : memoryEntries;
            Path file = "user".equals(category) ? userFile : memoryFile;
            
            boolean removed = entries.removeIf(e -> e.contains(substring));
            if (removed) {
                writeFile(file, entries);
                logger.debug("Deleted entry from {} matching: {}", category, substring);
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Replace a memory entry.
     */
    public boolean replace(String category, String oldSubstring, String newContent) {
        lock.writeLock().lock();
        try {
            // Security scan
            String scanResult = scanContent(newContent);
            if (scanResult != null) {
                logger.warn("Blocked memory replacement: {}", scanResult);
                return false;
            }
            
            List<String> entries = "user".equals(category) ? userEntries : memoryEntries;
            Path file = "user".equals(category) ? userFile : memoryFile;
            
            boolean replaced = false;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).contains(oldSubstring)) {
                    entries.set(i, newContent);
                    replaced = true;
                    break;
                }
            }
            
            if (replaced) {
                writeFile(file, entries);
                logger.debug("Replaced entry in {} matching: {}", category, oldSubstring);
            }
            return replaced;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // Private helper methods
    
    private boolean addEntry(String content, String category, int charLimit) {
        // Security scan
        String scanResult = scanContent(content);
        if (scanResult != null) {
            logger.warn("Blocked memory entry: {}", scanResult);
            return false;
        }
        
        lock.writeLock().lock();
        try {
            List<String> entries = "user".equals(category) ? userEntries : memoryEntries;
            Path file = "user".equals(category) ? userFile : memoryFile;
            
            // Check for similar existing entries
            entries.removeIf(e -> isSimilar(e, content));
            
            // Add new entry
            entries.add(content);
            
            // Prune to stay within character limit
            List<String> pruned = pruneEntries(entries, charLimit);
            
            if ("user".equals(category)) {
                userEntries = pruned;
            } else {
                memoryEntries = pruned;
            }
            
            // Persist to disk
            writeFile(file, pruned);
            
            logger.debug("Added {} entry: {}", category, 
                content.substring(0, Math.min(50, content.length())));
            return true;
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    private List<String> readFile(Path file) {
        try {
            if (!Files.exists(file)) {
                return new ArrayList<>();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                return new ArrayList<>();
            }
            
            // Split by delimiter
            String[] parts = content.split(ENTRY_DELIMITER, -1);
            return Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            logger.error("Failed to read file {}: {}", file, e.getMessage());
            return new ArrayList<>();
        }
    }
    
    private void writeFile(Path file, List<String> entries) {
        try {
            String content = String.join(ENTRY_DELIMITER, entries);
            if (!content.isEmpty()) {
                content = content + ENTRY_DELIMITER;
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("Failed to write file {}: {}", file, e.getMessage());
        }
    }
    
    private String renderBlock(String category, List<String> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(capitalize(category)).append(" Memory\n\n");
        
        for (String entry : entries) {
            sb.append("- ").append(entry.replace("\n", "\n  ")).append("\n");
        }
        
        return sb.toString();
    }
    
    private List<String> deduplicate(List<String> entries) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String entry : entries) {
            seen.add(entry);
        }
        return new ArrayList<>(seen);
    }
    
    private List<String> searchInEntries(List<String> entries, String query) {
        return entries.stream()
            .filter(e -> e.toLowerCase().contains(query))
            .collect(Collectors.toList());
    }
    
    private boolean isSimilar(String a, String b) {
        // Simple similarity check
        if (a.equalsIgnoreCase(b)) return true;
        if (a.length() > 20 && b.length() > 20) {
            return a.substring(0, 20).equalsIgnoreCase(b.substring(0, 20));
        }
        return false;
    }
    
    private List<String> pruneEntries(List<String> entries, int charLimit) {
        int totalChars = entries.stream().mapToInt(String::length).sum();
        
        while (totalChars > charLimit && !entries.isEmpty()) {
            // Remove oldest (first) entry
            String removed = entries.remove(0);
            totalChars -= removed.length();
        }
        
        return entries;
    }
    
    private String scanContent(String content) {
        // Check invisible unicode characters
        for (char c : content.toCharArray()) {
            if (INVISIBLE_CHARS.contains(c)) {
                return String.format("Blocked: content contains invisible unicode character U+%04X (possible injection).", (int) c);
            }
        }
        
        // Check threat patterns
        for (Pattern pattern : THREAT_PATTERNS) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return String.format("Blocked: content matches threat pattern '%s'. Memory entries are injected into the system prompt and must not contain injection or exfiltration payloads.", pattern.pattern());
            }
        }
        
        return null;
    }
    
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}