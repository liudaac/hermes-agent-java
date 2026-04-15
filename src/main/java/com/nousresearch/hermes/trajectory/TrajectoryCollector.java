package com.nousresearch.hermes.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Trajectory collector for learning from completed sessions.
 * 
 * Collects conversation trajectories, compresses them, and extracts
 * insights for automatic improvement.
 * 
 * Aligned with Python Hermes trajectory_compressor.py
 */
public class TrajectoryCollector {
    private static final Logger logger = LoggerFactory.getLogger(TrajectoryCollector.class);
    private static final ObjectMapper mapper = new ObjectMapper()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    
    private final Path trajectoriesDir;
    private final Path completedFile;
    private final Path failedFile;
    private final Path compressedDir;
    
    // Background processing queue
    private final BlockingQueue<TrajectoryEntry> pendingTrajectories = new LinkedBlockingQueue<>();
    private volatile boolean running = false;
    private Thread processingThread;
    
    // Session tracking
    private final Map<String, TrajectorySession> activeSessions = new HashMap<>();
    
    public TrajectoryCollector() {
        this.trajectoriesDir = Constants.getHermesHome().resolve("trajectories");
        this.completedFile = trajectoriesDir.resolve("trajectory_samples.jsonl");
        this.failedFile = trajectoriesDir.resolve("failed_trajectories.jsonl");
        this.compressedDir = trajectoriesDir.resolve("compressed");
        
        try {
            Files.createDirectories(trajectoriesDir);
            Files.createDirectories(compressedDir);
        } catch (IOException e) {
            logger.error("Failed to create trajectories directory: {}", e.getMessage());
        }
        
        startBackgroundProcessing();
    }
    
    /**
     * Start a new trajectory tracking session.
     */
    public void startSession(String sessionId, String model) {
        TrajectorySession session = new TrajectorySession(sessionId, model);
        activeSessions.put(sessionId, session);
        logger.debug("Started trajectory tracking for session: {}", sessionId);
    }
    
    /**
     * Add a message to the current session.
     */
    public void addMessage(String sessionId, ModelMessage message) {
        TrajectorySession session = activeSessions.get(sessionId);
        if (session != null) {
            session.addMessage(message);
        }
    }
    
    /**
     * End a session and queue for processing.
     */
    public void endSession(String sessionId, boolean completed) {
        TrajectorySession session = activeSessions.remove(sessionId);
        if (session != null) {
            TrajectoryEntry entry = session.toEntry(completed);
            pendingTrajectories.offer(entry);
            logger.debug("Queued trajectory for session: {} (completed={})", sessionId, completed);
        }
    }
    
    /**
     * Save trajectory immediately (synchronous).
     */
    public void saveTrajectory(TrajectoryEntry entry) {
        Path targetFile = entry.isCompleted() ? completedFile : failedFile;
        
        try {
            String json = mapper.writeValueAsString(entry);
            Files.writeString(targetFile, json + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            logger.info("Saved trajectory: {}", entry.getId());
        } catch (Exception e) {
            logger.error("Failed to save trajectory: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Load trajectories for training/analysis.
     */
    public List<TrajectoryEntry> loadTrajectories(boolean includeFailed, int limit) {
        List<TrajectoryEntry> trajectories = new ArrayList<>();
        
        // Load completed trajectories
        trajectories.addAll(loadFromFile(completedFile, limit));
        
        // Load failed trajectories if requested
        if (includeFailed) {
            trajectories.addAll(loadFromFile(failedFile, limit - trajectories.size()));
        }
        
        // Sort by timestamp descending
        trajectories.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
        
        return trajectories.stream()
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    /**
     * Get statistics about collected trajectories.
     */
    public TrajectoryStats getStats() {
        TrajectoryStats stats = new TrajectoryStats();
        
        try {
            if (Files.exists(completedFile)) {
                long completedCount = Files.lines(completedFile).count();
                stats.completedCount = (int) completedCount;
            }
            
            if (Files.exists(failedFile)) {
                long failedCount = Files.lines(failedFile).count();
                stats.failedCount = (int) failedCount;
            }
            
            stats.totalCount = stats.completedCount + stats.failedCount;
            
        } catch (Exception e) {
            logger.error("Failed to get trajectory stats: {}", e.getMessage());
        }
        
        return stats;
    }
    
    /**
     * Shutdown the collector.
     */
    public void shutdown() {
        running = false;
        if (processingThread != null) {
            processingThread.interrupt();
        }
        
        // Process remaining trajectories
        TrajectoryEntry entry;
        while ((entry = pendingTrajectories.poll()) != null) {
            saveTrajectory(entry);
        }
    }
    
    // Background processing
    private void startBackgroundProcessing() {
        running = true;
        processingThread = new Thread(this::processLoop);
        processingThread.setDaemon(true);
        processingThread.setName("trajectory-processor");
        processingThread.start();
    }
    
    private void processLoop() {
        while (running) {
            try {
                TrajectoryEntry entry = pendingTrajectories.take();
                processTrajectory(entry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error processing trajectory: {}", e.getMessage(), e);
            }
        }
    }
    
    private void processTrajectory(TrajectoryEntry entry) {
        // TODO: Apply compression if needed
        // TODO: Extract insights
        // For now, just save
        saveTrajectory(entry);
    }
    
    private List<TrajectoryEntry> loadFromFile(Path file, int limit) {
        List<TrajectoryEntry> entries = new ArrayList<>();
        
        if (!Files.exists(file)) {
            return entries;
        }
        
        try (Stream<String> lines = Files.lines(file)) {
            lines.limit(limit)
                .forEach(line -> {
                    try {
                        TrajectoryEntry entry = mapper.readValue(line, TrajectoryEntry.class);
                        entries.add(entry);
                    } catch (Exception e) {
                        logger.debug("Failed to parse trajectory entry: {}", e.getMessage());
                    }
                });
        } catch (Exception e) {
            logger.error("Failed to load trajectories from {}: {}", file, e.getMessage());
        }
        
        return entries;
    }
    
    // Inner class for active session tracking
    private static class TrajectorySession {
        private final String sessionId;
        private final String model;
        private final List<ModelMessage> messages = new ArrayList<>();
        private final Instant startTime;
        
        TrajectorySession(String sessionId, String model) {
            this.sessionId = sessionId;
            this.model = model;
            this.startTime = Instant.now();
        }
        
        void addMessage(ModelMessage message) {
            messages.add(message);
        }
        
        TrajectoryEntry toEntry(boolean completed) {
            TrajectoryEntry entry = new TrajectoryEntry();
            entry.setSessionId(sessionId);
            entry.setModel(model);
            entry.setCompleted(completed);
            entry.setConversations(new ArrayList<>(messages));
            entry.setMetadata(Map.of(
                "duration_seconds", java.time.Duration.between(startTime, Instant.now()).getSeconds(),
                "message_count", messages.size()
            ));
            return entry;
        }
    }
    
    // Stats class
    public static class TrajectoryStats {
        public int totalCount;
        public int completedCount;
        public int failedCount;
        
        @Override
        public String toString() {
            return String.format("Trajectories: %d total (%d completed, %d failed)",
                totalCount, completedCount, failedCount);
        }
    }
}
