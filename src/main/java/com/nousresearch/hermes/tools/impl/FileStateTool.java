package com.nousresearch.hermes.tools.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cross-agent file state coordination.
 * Mirrors Python tools/file_state.py
 * 
 * Prevents mangled edits when concurrent subagents touch the same file.
 * Tracks read/write stamps and provides locking for critical sections.
 */
public class FileStateTool {
    private static final Logger logger = LoggerFactory.getLogger(FileStateTool.class);
    
    private static final boolean DISABLED = "1".equals(System.getenv("HERMES_DISABLE_FILE_STATE_GUARD"));
    private static final int MAX_PATHS_PER_AGENT = 100;
    
    // Singleton instance
    private static final FileStateTool INSTANCE = new FileStateTool();
    
    // Per-agent read stamps: {task_id: {path: ReadStamp}}
    private final Map<String, Map<String, ReadStamp>> agentReads = new ConcurrentHashMap<>();
    
    // Last writer globally: {path: WriteStamp}
    private final Map<String, WriteStamp> lastWriter = new ConcurrentHashMap<>();
    
    // Per-path locks
    private final Map<String, Lock> pathLocks = new ConcurrentHashMap<>();
    
    // Global lock for registry operations
    private final Lock registryLock = new ReentrantLock();
    
    private FileStateTool() {}
    
    public static FileStateTool getInstance() {
        return INSTANCE;
    }
    
    /**
     * Record that an agent has read a file.
     * 
     * @param taskId Agent task ID
     * @param path File path
     * @param partial Whether this was a partial read
     */
    public void recordRead(String taskId, String path, boolean partial) {
        if (DISABLED) return;
        
        try {
            String resolvedPath = resolvePath(path);
            long mtime = getMtime(resolvedPath);
            long readTs = System.currentTimeMillis();
            
            Map<String, ReadStamp> agentPaths = agentReads.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>());
            
            // Limit paths per agent
            if (agentPaths.size() >= MAX_PATHS_PER_AGENT) {
                // Remove oldest entry
                String oldestPath = agentPaths.keySet().iterator().next();
                agentPaths.remove(oldestPath);
            }
            
            agentPaths.put(resolvedPath, new ReadStamp(mtime, readTs, partial));
            logger.debug("Recorded read: {} by {} (partial: {})", resolvedPath, taskId, partial);
            
        } catch (Exception e) {
            logger.debug("Error recording read for {}: {}", path, e.getMessage());
        }
    }
    
    /**
     * Note that an agent has written to a file.
     * 
     * @param taskId Agent task ID
     * @param path File path
     */
    public void noteWrite(String taskId, String path) {
        if (DISABLED) return;
        
        try {
            String resolvedPath = resolvePath(path);
            long writeTs = System.currentTimeMillis();
            
            registryLock.lock();
            try {
                lastWriter.put(resolvedPath, new WriteStamp(taskId, writeTs));
            } finally {
                registryLock.unlock();
            }
            
            logger.debug("Noted write: {} by {}", resolvedPath, taskId);
            
        } catch (Exception e) {
            logger.debug("Error noting write for {}: {}", path, e.getMessage());
        }
    }
    
    /**
     * Check if a file has been modified since an agent read it.
     * 
     * @param taskId Agent task ID
     * @param path File path
     * @return CheckResult with stale status and details
     */
    public CheckResult checkStale(String taskId, String path) {
        if (DISABLED) {
            return new CheckResult(false, null, "File state guard disabled");
        }
        
        try {
            String resolvedPath = resolvePath(path);
            
            // Check if this agent has read the file
            Map<String, ReadStamp> agentPaths = agentReads.get(taskId);
            if (agentPaths == null) {
                return new CheckResult(false, null, "Agent has not read this file");
            }
            
            ReadStamp readStamp = agentPaths.get(resolvedPath);
            if (readStamp == null) {
                return new CheckResult(false, null, "Agent has not read this file");
            }
            
            // Check if another agent has written to the file
            WriteStamp writer = lastWriter.get(resolvedPath);
            if (writer == null) {
                return new CheckResult(false, null, "No writes since read");
            }
            
            // If the writer is the same agent, not stale
            if (writer.taskId.equals(taskId)) {
                return new CheckResult(false, null, "Last writer is same agent");
            }
            
            // Check if write happened after read
            if (writer.writeTs > readStamp.readTs) {
                String message = String.format(
                    "File may be stale: %s was written by %s at %s after you read it at %s",
                    resolvedPath, writer.taskId, 
                    formatTimestamp(writer.writeTs), 
                    formatTimestamp(readStamp.readTs)
                );
                return new CheckResult(true, writer.taskId, message);
            }
            
            // Also check if file mtime has changed
            long currentMtime = getMtime(resolvedPath);
            if (currentMtime > readStamp.mtime) {
                String message = String.format(
                    "File mtime changed: %s was modified on disk after you read it",
                    resolvedPath
                );
                return new CheckResult(true, null, message);
            }
            
            return new CheckResult(false, null, "File is up to date");
            
        } catch (Exception e) {
            logger.debug("Error checking stale for {}: {}", path, e.getMessage());
            return new CheckResult(false, null, "Error checking: " + e.getMessage());
        }
    }
    
    /**
     * Get a lock for a specific path.
     * Use try-with-resources or lock/unlock manually.
     * 
     * @param path File path
     * @return Lock for the path
     */
    public Lock getPathLock(String path) {
        String resolvedPath = resolvePath(path);
        return pathLocks.computeIfAbsent(resolvedPath, k -> new ReentrantLock());
    }
    
    /**
     * Lock a path for critical section.
     * 
     * @param path File path
     */
    public void lockPath(String path) {
        getPathLock(path).lock();
    }
    
    /**
     * Unlock a path.
     * 
     * @param path File path
     */
    public void unlockPath(String path) {
        String resolvedPath = resolvePath(path);
        Lock lock = pathLocks.get(resolvedPath);
        if (lock != null) {
            lock.unlock();
        }
    }
    
    /**
     * Get writes since a specific timestamp.
     * 
     * @param taskId Agent task ID to exclude
     * @param sinceTs Timestamp
     * @param paths Optional path filter
     * @return Set of paths written
     */
    public Set<String> writesSince(String taskId, long sinceTs, Set<String> paths) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        
        registryLock.lock();
        try {
            for (Map.Entry<String, WriteStamp> entry : lastWriter.entrySet()) {
                String path = entry.getKey();
                WriteStamp stamp = entry.getValue();
                
                // Skip if filtering paths and not in list
                if (paths != null && !paths.isEmpty() && !paths.contains(path)) {
                    continue;
                }
                
                // Skip if same agent
                if (stamp.taskId.equals(taskId)) {
                    continue;
                }
                
                // Include if written after sinceTs
                if (stamp.writeTs > sinceTs) {
                    result.add(path);
                }
            }
        } finally {
            registryLock.unlock();
        }
        
        return result;
    }
    
    /**
     * Clear all state for an agent.
     * 
     * @param taskId Agent task ID
     */
    public void clearAgent(String taskId) {
        agentReads.remove(taskId);
        logger.debug("Cleared state for agent: {}", taskId);
    }
    
    /**
     * Clear all state (use with caution).
     */
    public void clearAll() {
        registryLock.lock();
        try {
            agentReads.clear();
            lastWriter.clear();
            pathLocks.clear();
            logger.debug("Cleared all file state");
        } finally {
            registryLock.unlock();
        }
    }
    
    // Private helpers
    
    private String resolvePath(String path) {
        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path;
        }
    }
    
    private long getMtime(String path) {
        try {
            Path filePath = Paths.get(path);
            if (Files.exists(filePath)) {
                FileTime mtime = Files.getLastModifiedTime(filePath);
                return mtime.toMillis();
            }
        } catch (IOException e) {
            // File doesn't exist or can't read
        }
        return 0;
    }
    
    private String formatTimestamp(long ts) {
        return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(ts));
    }
    
    // Inner classes
    
    /**
     * Read stamp: (mtime, read_ts, partial)
     */
    public static class ReadStamp {
        public final long mtime;
        public final long readTs;
        public final boolean partial;
        
        public ReadStamp(long mtime, long readTs, boolean partial) {
            this.mtime = mtime;
            this.readTs = readTs;
            this.partial = partial;
        }
    }
    
    /**
     * Write stamp: (task_id, write_ts)
     */
    public static class WriteStamp {
        public final String taskId;
        public final long writeTs;
        
        public WriteStamp(String taskId, long writeTs) {
            this.taskId = taskId;
            this.writeTs = writeTs;
        }
    }
    
    /**
     * Check result for stale detection.
     */
    public static class CheckResult {
        public final boolean stale;
        public final String otherAgent;
        public final String message;
        
        public CheckResult(boolean stale, String otherAgent, String message) {
            this.stale = stale;
            this.otherAgent = otherAgent;
            this.message = message;
        }
        
        public boolean isStale() {
            return stale;
        }
    }
}
