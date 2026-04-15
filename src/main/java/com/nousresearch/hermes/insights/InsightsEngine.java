package com.nousresearch.hermes.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.trajectory.TrajectoryCollector;
import com.nousresearch.hermes.trajectory.TrajectoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Insights engine for analyzing agent usage and performance.
 * 
 * Analyzes trajectory data to produce:
 * - Token consumption statistics
 * - Tool usage patterns
 * - Cost estimates
 * - Activity trends
 * - Model/platform breakdowns
 * 
 * Aligned with Python Hermes agent/insights.py
 */
public class InsightsEngine {
    private static final Logger logger = LoggerFactory.getLogger(InsightsEngine.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final TrajectoryCollector trajectoryCollector;
    private final ConfigManager config;
    
    // Pricing data (simplified - should be loaded from config)
    private final Map<String, ModelPricing> pricing = new HashMap<>();
    
    public InsightsEngine(Path dataDir) {
        this.trajectoryCollector = new TrajectoryCollector();
        this.config = ConfigManager.getInstance();
        
        // Initialize default pricing
        initializePricing();
    }
    
    /**
     * Generate insights report for a time period.
     */
    public InsightsReport generateReport(int days) {
        Instant since = Instant.now().minusSeconds(days * 24 * 60 * 60);
        
        InsightsReport report = new InsightsReport();
        report.setPeriodDays(days);
        report.setGeneratedAt(Instant.now());
        
        try {
            // Get trajectories
            List<TrajectoryEntry> trajectories = trajectoryCollector.loadTrajectories(true, 1000);
            
            // Filter by date
            List<TrajectoryEntry> recentTrajectories = trajectories.stream()
                .filter(t -> t.getTimestamp().isAfter(since))
                .collect(Collectors.toList());
            
            report.setTotalSessions(recentTrajectories.size());
            
            if (recentTrajectories.isEmpty()) {
                return report;
            }
            
            // Calculate metrics
            calculateTokenMetrics(report, recentTrajectories);
            calculateCostMetrics(report, recentTrajectories);
            calculateModelMetrics(report, recentTrajectories);
            calculateActivityTrends(report, recentTrajectories, days);
            
        } catch (Exception e) {
            logger.error("Failed to generate insights: {}", e.getMessage(), e);
        }
        
        return report;
    }
    
    /**
     * Format report for terminal display.
     */
    public String formatTerminal(InsightsReport report) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("╔══════════════════════════════════════════════════════════════╗\n");
        sb.append("║                    Hermes Insights Report                    ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════╝\n\n");
        
        sb.append(String.format("Period: Last %d days\n", report.getPeriodDays()));
        sb.append(String.format("Generated: %s\n\n", report.getGeneratedAt()));
        
        // Overview
        sb.append("━━━ Overview ━━━\n");
        sb.append(String.format("  Total Sessions: %d\n", report.getTotalSessions()));
        sb.append(String.format("  Total Messages: %d\n", report.getTotalMessages()));
        sb.append(String.format("  Total Tokens: %s\n", formatNumber(report.getTotalTokens())));
        sb.append(String.format("  Est. Cost: $%.2f\n\n", report.getTotalCost()));
        
        // Token breakdown
        sb.append("━━━ Token Usage ━━━\n");
        sb.append(String.format("  Input:  %s (%.1f%%)\n", 
            formatNumber(report.getInputTokens()),
            report.getTotalTokens() > 0 ? (100.0 * report.getInputTokens() / report.getTotalTokens()) : 0));
        sb.append(String.format("  Output: %s (%.1f%%)\n\n",
            formatNumber(report.getOutputTokens()),
            report.getTotalTokens() > 0 ? (100.0 * report.getOutputTokens() / report.getTotalTokens()) : 0));
        
        // Model breakdown
        if (!report.getModelUsage().isEmpty()) {
            sb.append("━━━ Model Usage ━━━\n");
            report.getModelUsage().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> sb.append(String.format("  %-30s %s tokens\n", 
                    e.getKey() + ":", formatNumber(e.getValue()))));
            sb.append("\n");
        }
        
        // Daily activity
        if (!report.getDailyActivity().isEmpty()) {
            sb.append("━━━ Daily Activity ━━━\n");
            report.getDailyActivity().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    String bar = "█".repeat(Math.max(1, e.getValue() / 10));
                    sb.append(String.format("  %s %s %d\n", e.getKey(), bar, e.getValue()));
                });
        }
        
        return sb.toString();
    }
    
    // Private calculation methods
    
    private void calculateTokenMetrics(InsightsReport report, List<TrajectoryEntry> trajectories) {
        long totalTokens = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        long totalMessages = 0;
        
        for (TrajectoryEntry entry : trajectories) {
            if (entry.getConversations() == null) continue;
            
            for (var msg : entry.getConversations()) {
                if (msg == null || msg.getContent() == null) continue;
                
                int chars = msg.getContent().length();
                int tokens = chars / 4; // Rough estimate
                
                totalMessages++;
                totalTokens += tokens;
                
                String role = msg.getRole();
                if ("user".equals(role)) {
                    inputTokens += tokens;
                } else if ("assistant".equals(role)) {
                    outputTokens += tokens;
                }
            }
        }
        
        report.setTotalTokens(totalTokens);
        report.setInputTokens(inputTokens);
        report.setOutputTokens(outputTokens);
        report.setTotalMessages(totalMessages);
    }
    
    private void calculateCostMetrics(InsightsReport report, List<TrajectoryEntry> trajectories) {
        double totalCost = 0;
        
        for (TrajectoryEntry entry : trajectories) {
            String model = entry.getModel() != null ? entry.getModel() : "unknown";
            ModelPricing p = pricing.getOrDefault(model, pricing.get("default"));
            
            if (entry.getConversations() == null) continue;
            
            // Estimate cost based on token counts
            int chars = entry.getConversations().stream()
                .filter(m -> m != null && m.getContent() != null)
                .mapToInt(m -> m.getContent().length())
                .sum();
            int tokens = chars / 4;
            
            double cost = (tokens * p.inputPrice) / 1000000.0;
            totalCost += cost;
        }
        
        report.setTotalCost(totalCost);
    }
    
    private void calculateModelMetrics(InsightsReport report, List<TrajectoryEntry> trajectories) {
        Map<String, Long> modelUsage = new HashMap<>();
        
        for (TrajectoryEntry entry : trajectories) {
            String model = entry.getModel() != null ? entry.getModel() : "unknown";
            
            // Count tokens for this entry
            if (entry.getConversations() == null) continue;
            
            int chars = entry.getConversations().stream()
                .filter(m -> m != null && m.getContent() != null)
                .mapToInt(m -> m.getContent().length())
                .sum();
            int tokens = chars / 4;
            
            modelUsage.merge(model, (long) tokens, Long::sum);
        }
        
        report.setModelUsage(modelUsage);
    }
    
    private void calculateActivityTrends(InsightsReport report, List<TrajectoryEntry> trajectories, int days) {
        Map<String, Integer> dailyActivity = new TreeMap<>();
        
        // Initialize all days
        for (int i = days - 1; i >= 0; i--) {
            String date = LocalDate.now().minusDays(i).toString();
            dailyActivity.put(date, 0);
        }
        
        // Count sessions per day
        for (TrajectoryEntry entry : trajectories) {
            if (entry.getTimestamp() == null) continue;
            
            String date = entry.getTimestamp().toString().substring(0, 10);
            dailyActivity.merge(date, 1, Integer::sum);
        }
        
        report.setDailyActivity(dailyActivity);
    }
    
    private void initializePricing() {
        // Default pricing per 1M tokens (input)
        pricing.put("claude-opus-4-20250514", new ModelPricing(15.0, 75.0));
        pricing.put("claude-sonnet-4-20250514", new ModelPricing(3.0, 15.0));
        pricing.put("gpt-4o", new ModelPricing(5.0, 15.0));
        pricing.put("gpt-4o-mini", new ModelPricing(0.15, 0.6));
        pricing.put("gemini-1.5-pro", new ModelPricing(3.5, 10.5));
        pricing.put("gemini-1.5-flash", new ModelPricing(0.075, 0.3));
        pricing.put("default", new ModelPricing(1.0, 3.0));
    }
    
    private String formatNumber(long n) {
        if (n >= 1_000_000) {
            return String.format("%.2fM", n / 1_000_000.0);
        } else if (n >= 1_000) {
            return String.format("%.1fk", n / 1_000.0);
        }
        return String.valueOf(n);
    }
    
    // Inner classes
    
    private static class ModelPricing {
        final double inputPrice;
        final double outputPrice;
        
        ModelPricing(double inputPrice, double outputPrice) {
            this.inputPrice = inputPrice;
            this.outputPrice = outputPrice;
        }
    }
    
    public static class InsightsReport {
        private int periodDays;
        private Instant generatedAt;
        private int totalSessions;
        private long totalMessages;
        private long totalTokens;
        private long inputTokens;
        private long outputTokens;
        private double totalCost;
        private Map<String, Long> toolUsage = new HashMap<>();
        private Map<String, Long> modelUsage = new HashMap<>();
        private Map<String, Integer> dailyActivity = new HashMap<>();
        
        // Getters and Setters
        public int getPeriodDays() { return periodDays; }
        public void setPeriodDays(int periodDays) { this.periodDays = periodDays; }
        
        public Instant getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
        
        public int getTotalSessions() { return totalSessions; }
        public void setTotalSessions(int totalSessions) { this.totalSessions = totalSessions; }
        
        public long getTotalMessages() { return totalMessages; }
        public void setTotalMessages(long totalMessages) { this.totalMessages = totalMessages; }
        
        public long getTotalTokens() { return totalTokens; }
        public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }
        
        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
        
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
        
        public double getTotalCost() { return totalCost; }
        public void setTotalCost(double totalCost) { this.totalCost = totalCost; }
        
        public Map<String, Long> getToolUsage() { return toolUsage; }
        public void setToolUsage(Map<String, Long> toolUsage) { this.toolUsage = toolUsage; }
        
        public Map<String, Long> getModelUsage() { return modelUsage; }
        public void setModelUsage(Map<String, Long> modelUsage) { this.modelUsage = modelUsage; }
        
        public Map<String, Integer> getDailyActivity() { return dailyActivity; }
        public void setDailyActivity(Map<String, Integer> dailyActivity) { this.dailyActivity = dailyActivity; }
    }
}
