package com.nousresearch.hermes.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.skills.SkillManager;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Knowledge extractor for automatic learning from sessions.
 * 
 * Extracts insights from completed conversations and:
 * - Saves important facts to MEMORY.md
 * - Creates skills from successful workflows
 * - Identifies patterns for improvement
 * 
 * Aligned with Python Hermes memory_provider.py hooks:
 * - on_session_end()
 * - on_pre_compress()
 */
public class KnowledgeExtractor {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeExtractor.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final MemoryManager memoryManager;
    private final SkillManager skillManager;
    private final ConfigManager config;
    private final OkHttpClient httpClient;
    
    // Extraction settings
    private final String extractionModel;
    private final boolean autoExtractEnabled;
    private final int maxInsightsPerSession;
    private final int minMessagesForExtraction;
    
    public KnowledgeExtractor(MemoryManager memoryManager, SkillManager skillManager) {
        this.memoryManager = memoryManager;
        this.skillManager = skillManager;
        this.config = ConfigManager.getInstance();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        
        this.extractionModel = config.getString("learning.extraction_model", "google/gemini-flash-1.5");
        this.autoExtractEnabled = config.getBoolean("learning.auto_extract", true);
        this.maxInsightsPerSession = config.getInt("learning.max_insights", 5);
        this.minMessagesForExtraction = config.getInt("learning.min_messages", 4);
    }
    
    /**
     * Called when a session ends - extract knowledge from full conversation.
     * Corresponds to Python: on_session_end()
     */
    public ExtractionResult onSessionEnd(String sessionId, List<ModelMessage> messages) {
        if (!autoExtractEnabled) {
            return ExtractionResult.empty();
        }
        
        if (messages.size() < minMessagesForExtraction) {
            logger.debug("Session too short for extraction: {} messages", messages.size());
            return ExtractionResult.empty();
        }
        
        logger.info("Extracting knowledge from session: {} ({} messages)", sessionId, messages.size());
        
        ExtractionResult result = new ExtractionResult();
        
        try {
            // Extract insights
            List<String> insights = extractInsights(messages);
            result.setInsights(insights);
            
            // Save important facts to memory
            for (String insight : insights) {
                if (shouldSaveToMemory(insight)) {
                    memoryManager.addMemory(insight);
                    result.addMemorySaved(insight);
                }
            }
            
            // Check for skill-worthy patterns
            Optional<String> skillContent = extractSkillPattern(messages);
            if (skillContent.isPresent()) {
                result.setSkillCandidate(skillContent.get());
            }
            
            logger.info("Extracted {} insights, saved {} memories", 
                insights.size(), result.getMemoriesSaved().size());
                
        } catch (Exception e) {
            logger.error("Failed to extract knowledge: {}", e.getMessage(), e);
        }
        
        return result;
    }
    
    /**
     * Called before context compression - extract from messages about to be discarded.
     * Corresponds to Python: on_pre_compress()
     */
    public String onPreCompress(List<ModelMessage> messagesToCompress) {
        if (!autoExtractEnabled || messagesToCompress.isEmpty()) {
            return "";
        }
        
        logger.debug("Pre-compression extraction for {} messages", messagesToCompress.size());
        
        try {
            // Build conversation text
            StringBuilder conversation = new StringBuilder();
            for (ModelMessage msg : messagesToCompress) {
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    conversation.append(msg.getRole()).append(": ")
                        .append(msg.getContent()).append("\n\n");
                }
            }
            
            // Extract key facts before compression
            String prompt = buildCompressionExtractionPrompt(conversation.toString());
            String extracted = callExtractionModel(prompt);
            
            if (extracted != null && !extracted.trim().isEmpty()) {
                // Save extracted insights
                for (String fact : extracted.split("\\n")) {
                    if (fact.trim().length() > 20) {
                        memoryManager.addMemory(fact.trim());
                    }
                }
                return extracted;
            }
            
        } catch (Exception e) {
            logger.error("Pre-compression extraction failed: {}", e.getMessage());
        }
        
        return "";
    }
    
    /**
     * Extract insights from a conversation.
     */
    public List<String> extractInsights(List<ModelMessage> messages) {
        String conversation = formatConversation(messages);
        String prompt = buildInsightExtractionPrompt(conversation);
        
        try {
            String response = callExtractionModel(prompt);
            return parseInsights(response);
        } catch (Exception e) {
            logger.error("Insight extraction failed: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Extract a skill pattern from a successful workflow.
     */
    public Optional<String> extractSkillPattern(List<ModelMessage> messages) {
        // Check if this looks like a skill-worthy workflow
        if (!isSkillWorthyWorkflow(messages)) {
            return Optional.empty();
        }
        
        String conversation = formatConversation(messages);
        String prompt = buildSkillExtractionPrompt(conversation);
        
        try {
            String response = callExtractionModel(prompt);
            if (response != null && response.length() > 100) {
                return Optional.of(response);
            }
        } catch (Exception e) {
            logger.error("Skill extraction failed: {}", e.getMessage());
        }
        
        return Optional.empty();
    }
    
    /**
     * Create a skill from extracted pattern.
     */
    public boolean createSkillFromPattern(String name, String description, String content, List<String> tags) {
        try {
            SkillManager.Skill skill = skillManager.createSkill(name, description, content, tags, Map.of(
                "auto_created", true,
                "created_at", java.time.Instant.now().toString()
            ));
            
            if (skill != null) {
                logger.info("Auto-created skill: {}", name);
                return true;
            }
        } catch (Exception e) {
            logger.error("Failed to create skill: {}", e.getMessage());
        }
        
        return false;
    }
    
    // Private helper methods
    
    private String callExtractionModel(String prompt) throws Exception {
        String apiKey = config.getApiKey();
        String baseUrl = config.getBaseUrl();
        
        Map<String, Object> body = Map.of(
            "model", extractionModel,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", 2000,
            "temperature", 0.3
        );
        
        Request request = new Request.Builder()
            .url(baseUrl + "/chat/completions")
            .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Extraction API error: " + response.code());
            }
            
            var result = mapper.readTree(response.body().string());
            return result.path("choices").get(0).path("message").path("content").asText();
        }
    }
    
    private String formatConversation(List<ModelMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ModelMessage msg : messages) {
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                sb.append(msg.getRole().toUpperCase()).append(": ")
                    .append(msg.getContent()).append("\n\n");
            }
        }
        return sb.toString();
    }
    
    private List<String> parseInsights(String response) {
        List<String> insights = new ArrayList<>();
        
        if (response == null || response.trim().isEmpty()) {
            return insights;
        }
        
        // Parse numbered or bulleted list
        for (String line : response.split("\\n")) {
            line = line.trim();
            // Remove numbering (1. or - or *)
            line = line.replaceFirst("^\\d+\\.\\s*", "")
                      .replaceFirst("^[-*]\\s*", "");
            if (line.length() > 20 && line.length() < 500) {
                insights.add(line);
            }
        }
        
        return insights.stream()
            .limit(maxInsightsPerSession)
            .collect(java.util.stream.Collectors.toList());
    }
    
    private boolean shouldSaveToMemory(String insight) {
        // Filter out low-value insights
        if (insight.length() < 30) return false;
        
        // Skip if it's just a summary
        String lower = insight.toLowerCase();
        if (lower.contains("summary") || lower.contains("overview")) return false;
        
        // Check for factual content
        return lower.contains("user") || lower.contains("prefer") || 
               lower.contains("config") || lower.contains("project") ||
               lower.contains("important") || lower.contains("note");
    }
    
    private boolean isSkillWorthyWorkflow(List<ModelMessage> messages) {
        // Check if conversation demonstrates a reusable pattern
        int toolCalls = 0;
        int userMessages = 0;
        
        for (ModelMessage msg : messages) {
            if ("user".equals(msg.getRole())) userMessages++;
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                toolCalls += msg.getToolCalls().size();
            }
        }
        
        // Skills should have multiple tool calls and be substantial
        return toolCalls >= 3 && userMessages >= 2 && messages.size() >= 6;
    }
    
    private String buildInsightExtractionPrompt(String conversation) {
        return """
            Analyze this conversation and extract 3-5 key facts or insights that should be remembered.
            
            Focus on:
            - User preferences or requirements
            - Important decisions made
            - Configuration details
            - Project-specific information
            - Patterns or workflows that might be reused
            
            Format as a numbered list. Be specific and actionable.
            
            Conversation:
            %s
            
            Key insights to remember:
            """.formatted(conversation);
    }
    
    private String buildCompressionExtractionPrompt(String conversation) {
        return """
            This conversation is about to be compressed/summarized.
            Extract the most important facts that should NOT be lost.
            
            Focus on:
            - Critical user requirements
            - Key decisions or conclusions
            - Important context for future turns
            
            Format as a bulleted list. Be concise.
            
            Conversation to preserve:
            %s
            
            Important facts to preserve:
            """.formatted(conversation);
    }
    
    private String buildSkillExtractionPrompt(String conversation) {
        return """
            This conversation demonstrates a successful workflow.
            Create a skill documentation that captures this pattern.
            
            Include:
            - When to use this approach
            - Step-by-step instructions
            - Common pitfalls or variations
            - Example usage
            
            Format as markdown documentation.
            
            Conversation demonstrating the workflow:
            %s
            
            Skill documentation:
            """.formatted(conversation);
    }
    
    // Result class
    public static class ExtractionResult {
        private List<String> insights = new ArrayList<>();
        private List<String> memoriesSaved = new ArrayList<>();
        private String skillCandidate;
        
        public static ExtractionResult empty() {
            return new ExtractionResult();
        }
        
        public List<String> getInsights() { return insights; }
        public void setInsights(List<String> insights) { this.insights = insights; }
        
        public List<String> getMemoriesSaved() { return memoriesSaved; }
        public void addMemorySaved(String memory) { this.memoriesSaved.add(memory); }
        
        public String getSkillCandidate() { return skillCandidate; }
        public void setSkillCandidate(String skillCandidate) { this.skillCandidate = skillCandidate; }
        
        public boolean hasSkillCandidate() {
            return skillCandidate != null && !skillCandidate.isEmpty();
        }
    }
}
