package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Context compression for managing token limits.
 * Summarizes older messages to fit within context window.
 */
public class ContextCompressor {
    private static final Logger logger = LoggerFactory.getLogger(ContextCompressor.class);
    
    // Token estimation: ~4 chars per token (rough estimate)
    private static final double CHARS_PER_TOKEN = 4.0;
    
    private final int maxTokens;
    private final int targetTokens;
    
    public ContextCompressor(int maxTokens) {
        this.maxTokens = maxTokens;
        this.targetTokens = (int) (maxTokens * 0.8); // Target 80% of max
    }
    
    /**
     * Compress conversation history to fit within token limit.
     */
    public List<ModelMessage> compress(List<ModelMessage> history) {
        int estimatedTokens = estimateTokens(history);
        
        if (estimatedTokens <= targetTokens) {
            return history; // No compression needed
        }
        
        logger.debug("Compressing context: {} tokens -> target {} tokens", 
            estimatedTokens, targetTokens);
        
        // Strategy: Keep system messages and recent messages, summarize middle
        List<ModelMessage> compressed = new ArrayList<>();
        
        // Always keep system messages
        List<ModelMessage> systemMessages = new ArrayList<>();
        List<ModelMessage> otherMessages = new ArrayList<>();
        
        for (ModelMessage msg : history) {
            if ("system".equals(msg.getRole())) {
                systemMessages.add(msg);
            } else {
                otherMessages.add(msg);
            }
        }
        
        compressed.addAll(systemMessages);
        
        // If still too large, we need to summarize
        int systemTokens = estimateTokens(systemMessages);
        int availableForHistory = targetTokens - systemTokens;
        
        if (availableForHistory <= 0) {
            // System messages alone exceed limit
            logger.warn("System messages exceed token limit!");
            return compressed;
        }
        
        // Keep recent messages, summarize older ones
        List<ModelMessage> recentMessages = extractRecentMessages(otherMessages, availableForHistory);
        
        // Summarize the rest
        List<ModelMessage> olderMessages = otherMessages.subList(0, 
            Math.max(0, otherMessages.size() - recentMessages.size()));
        
        if (!olderMessages.isEmpty()) {
            String summary = summarizeMessages(olderMessages);
            compressed.add(ModelMessage.system("[Earlier conversation summary]: " + summary));
        }
        
        compressed.addAll(recentMessages);
        
        int finalTokens = estimateTokens(compressed);
        logger.debug("Compressed to {} tokens", finalTokens);
        
        return compressed;
    }
    
    /**
     * Extract recent messages that fit within token budget.
     */
    private List<ModelMessage> extractRecentMessages(List<ModelMessage> messages, int tokenBudget) {
        List<ModelMessage> recent = new ArrayList<>();
        int tokens = 0;
        
        // Work backwards from most recent
        for (int i = messages.size() - 1; i >= 0; i--) {
            ModelMessage msg = messages.get(i);
            int msgTokens = estimateTokens(List.of(msg));
            
            if (tokens + msgTokens > tokenBudget) {
                break;
            }
            
            recent.add(0, msg); // Add to front to maintain order
            tokens += msgTokens;
        }
        
        return recent;
    }
    
    /**
     * Summarize a list of messages.
     * In a real implementation, this would use the LLM to generate a summary.
     * For now, we create a simple text summary.
     */
    private String summarizeMessages(List<ModelMessage> messages) {
        StringBuilder summary = new StringBuilder();
        
        // Count messages by role
        int userMsgs = 0;
        int assistantMsgs = 0;
        int toolMsgs = 0;
        
        for (ModelMessage msg : messages) {
            switch (msg.getRole()) {
                case "user" -> userMsgs++;
                case "assistant" -> assistantMsgs++;
                case "tool" -> toolMsgs++;
            }
        }
        
        summary.append("This conversation included ");
        summary.append(userMsgs).append(" user messages, ");
        summary.append(assistantMsgs).append(" assistant responses, ");
        summary.append("and ").append(toolMsgs).append(" tool interactions. ");
        
        // Extract key topics (simple approach: look for keywords)
        String allContent = messages.stream()
            .map(ModelMessage::getContent)
            .filter(c -> c != null)
            .reduce("", String::concat);
        
        if (allContent.length() > 200) {
            summary.append("Key points: ");
            // Extract first sentence or first 100 chars
            String excerpt = allContent.substring(0, Math.min(200, allContent.length()))
                .replaceAll("\\s+", " ")
                .trim();
            summary.append(excerpt);
            if (allContent.length() > 200) {
                summary.append("...");
            }
        }
        
        return summary.toString();
    }
    
    /**
     * Estimate token count for messages.
     */
    public int estimateTokens(List<ModelMessage> messages) {
        int totalChars = 0;
        
        for (ModelMessage msg : messages) {
            // Role and content
            if (msg.getRole() != null) {
                totalChars += msg.getRole().length();
            }
            if (msg.getContent() != null) {
                totalChars += msg.getContent().length();
            }
            
            // Tool calls
            if (msg.getToolCalls() != null) {
                for (var tc : msg.getToolCalls()) {
                    if (tc.getFunction() != null) {
                        if (tc.getFunction().getName() != null) {
                            totalChars += tc.getFunction().getName().length();
                        }
                        if (tc.getFunction().getArguments() != null) {
                            totalChars += tc.getFunction().getArguments().length();
                        }
                    }
                }
            }
            
            // Tool results
            if (msg.getToolCallId() != null) {
                totalChars += msg.getToolCallId().length();
            }
            
            // Overhead per message
            totalChars += 10;
        }
        
        return (int) (totalChars / CHARS_PER_TOKEN);
    }
    
    /**
     * Check if compression is needed.
     */
    public boolean needsCompression(List<ModelMessage> messages) {
        return estimateTokens(messages) > targetTokens;
    }
    
    /**
     * Get compression stats.
     */
    public CompressionStats getStats(List<ModelMessage> original, List<ModelMessage> compressed) {
        CompressionStats stats = new CompressionStats();
        stats.originalTokens = estimateTokens(original);
        stats.compressedTokens = estimateTokens(compressed);
        stats.reductionPercent = (1.0 - (double) stats.compressedTokens / stats.originalTokens) * 100;
        stats.messagesRemoved = original.size() - compressed.size();
        return stats;
    }
    
    public static class CompressionStats {
        public int originalTokens;
        public int compressedTokens;
        public double reductionPercent;
        public int messagesRemoved;
        
        @Override
        public String toString() {
            return String.format("Context compression: %d -> %d tokens (%.1f%% reduction, %d messages)",
                originalTokens, compressedTokens, reductionPercent, messagesRemoved);
        }
    }
}