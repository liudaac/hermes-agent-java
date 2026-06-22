package com.nousresearch.hermes.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.stream.Collectors;

/**
 * Knowledge extractor for automatic learning from sessions.
 *
 * <h3>What changed (2026-06-04 upgrade)</h3>
 * <ul>
 *   <li>LLM-based structured extraction via {@link StructuredExtractionPrompts}</li>
 *   <li>Confidence filtering via {@link ExtractionPolicy}</li>
 *   <li>Multi-sink routing: MEMORY.md, USER.md, skill candidates, anti-patterns</li>
 * </ul>
 * The old heuristic API ({@link #extractInsights(List)}) still works but delegates
 * through the same structured pipeline under the hood.
 */
public class KnowledgeExtractor {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final MemoryManager memoryManager;
    private final SkillManager skillManager;
    private final ConfigManager config;
    private final OkHttpClient httpClient;
    private final ExtractionPolicy policy;

    // Extraction settings
    private final String extractionModel;
    private final boolean autoExtractEnabled;
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
        this.minMessagesForExtraction = config.getInt("learning.min_messages", 4);

        // Load policy from config; fall back to defaults.
        this.policy = new ExtractionPolicy(
                config.getDouble("learning.confidence.memory", 0.70),
                config.getDouble("learning.confidence.user_profile", 0.75),
                config.getDouble("learning.confidence.skill", 0.80),
                config.getInt("learning.max_items_per_bucket", 5),
                config.getBoolean("learning.llm_extraction", true)
        );
    }

    // ========================================================================
    // PUBLIC API — backward compatible
    // ========================================================================

    /**
     * Called when a session ends. Uses LLM to extract structured knowledge,
     * filters by confidence, and routes to the appropriate sinks.
     */
    public ExtractionResult onSessionEnd(String sessionId, List<ModelMessage> messages) {
        if (!autoExtractEnabled || messages.size() < minMessagesForExtraction) {
            logger.debug("Skipping extraction (auto={}, messages={})", autoExtractEnabled, messages.size());
            return ExtractionResult.empty();
        }
        logger.info("Extracting knowledge from session: {} ({} messages)", sessionId, messages.size());

        ExtractionResult result = new ExtractionResult();

        // 1. LLM structured extraction
        String transcript = formatConversation(messages);
        Optional<ExtractedKnowledge> opt = callStructuredExtraction(transcript);

        if (opt.isEmpty()) {
            // Fallback: heuristic parse so we never silently drop a session.
            return legacyFallback(messages);
        }

        ExtractedKnowledge ek = opt.get();

        // 2. Filter & route each bucket
        routeFacts(ek.getFacts(), result);
        routeUserProfile(ek.getUserProfile(), result);
        routeSkillHints(ek.getSkillHints(), result);
        routeAntiPatterns(ek.getAntiPatterns(), result);

        logger.info("Structured extraction: {} facts, {} profile, {} skills, {} anti-patterns",
                ek.getFacts().size(), ek.getUserProfile().size(),
                ek.getSkillHints().size(), ek.getAntiPatterns().size());

        return result;
    }

    /**
     * @deprecated Use {@link #onSessionEnd(String, List)} which now does
     * structured extraction under the hood.
     */
    public List<String> extractInsights(List<ModelMessage> messages) {
        String transcript = formatConversation(messages);
        Optional<ExtractedKnowledge> opt = callStructuredExtraction(transcript);
        if (opt.isEmpty()) {
            return List.of();
        }
        // Flatten all knowledge items into a single string list
        List<String> all = new ArrayList<>();
        for (var item : filterBy(opt.get().getFacts(), policy.getMemoryConfidenceThreshold())) {
            all.add(item.getContent());
        }
        for (var item : filterBy(opt.get().getUserProfile(), policy.getUserProfileConfidenceThreshold())) {
            all.add("[USER] " + item.getContent());
        }
        return all.stream().limit(policy.getMaxItemsPerBucket()).collect(Collectors.toList());
    }

    /**
     * Called before context compression — extract salvageable facts from
     * messages about to be discarded.
     */
    public String onPreCompress(List<ModelMessage> messagesToCompress) {
        if (!autoExtractEnabled || messagesToCompress.isEmpty()) return "";
        String transcript = formatConversation(messagesToCompress);
        String prompt = StructuredExtractionPrompts.preCompressUserPrompt(transcript);
        try {
            String raw = callRawExtraction(prompt);
            ExtractedKnowledge ek = MAPPER.readValue(raw, ExtractedKnowledge.class);
            for (var item : filterBy(ek.getFacts(), policy.getMemoryConfidenceThreshold())) {
                memoryManager.addMemory(item.getContent());
            }
            for (var item : filterBy(ek.getUserProfile(), policy.getUserProfileConfidenceThreshold())) {
                memoryManager.addUser(item.getContent());
            }
            return raw;
        } catch (Exception e) {
            logger.warn("Pre-compression structured extraction failed: {}", e.getMessage());
        }
        return "";
    }

    /**
     * Check if messages describe a skill-worthy workflow (heuristic guard).
     */
    public Optional<String> extractSkillPattern(List<ModelMessage> messages) {
        if (!isSkillWorthyWorkflow(messages)) return Optional.empty();
        String transcript = formatConversation(messages);
        Optional<ExtractedKnowledge> opt = callStructuredExtraction(transcript);
        if (opt.isPresent() && !opt.get().getSkillHints().isEmpty()) {
            var top = opt.get().getSkillHints().get(0);
            if (top.getConfidence() >= policy.getSkillConfidenceThreshold()) {
                String md = "# " + top.getName() + "\n\n"
                        + top.getDescription() + "\n\n"
                        + (top.getContent() != null ? top.getContent() : "");
                return Optional.of(md);
            }
        }
        return Optional.empty();
    }

    // ========================================================================
    // INTERNALS
    // ========================================================================

    /** LLM call returning a parsed {@link ExtractedKnowledge} (empty = failure). */
    private Optional<ExtractedKnowledge> callStructuredExtraction(String transcript) {
        if (!policy.isLlmEnabled()) return Optional.empty();
        List<ModelMessage> chatMessages = buildChatMessages(transcript);
        try {
            String raw = callAPI(chatMessages);
            ExtractedKnowledge ek = MAPPER.readValue(raw, ExtractedKnowledge.class);
            return Optional.of(ek);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse structured JSON from LLM, response: {}...",
                    e.getMessage().substring(0, Math.min(120, e.getMessage().length())));
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("LLM extraction call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** LLM call returning raw text (for pre-compress). */
    private String callRawExtraction(String userPrompt) throws Exception {
        List<ModelMessage> msgs = List.of(
                new ModelMessage("system", StructuredExtractionPrompts.SYSTEM),
                new ModelMessage("user", userPrompt)
        );
        return callAPI(msgs);
    }

    private List<ModelMessage> buildChatMessages(String transcript) {
        return List.of(
                new ModelMessage("system", StructuredExtractionPrompts.SYSTEM),
                new ModelMessage("user",
                        StructuredExtractionPrompts.userPrompt(transcript, policy.getMaxItemsPerBucket()))
        );
    }

    private String callAPI(List<ModelMessage> messages) throws Exception {
        String apiKey = config.getApiKey();
        String baseUrl = config.getBaseUrl();

        // Convert messages to JSON
        List<Map<String, String>> bodyMessages = new ArrayList<>();
        for (ModelMessage msg : messages) {
            String role = msg.getRole() != null ? msg.getRole() : "user";
            String content = msg.getContent() != null ? msg.getContent() : "";
            bodyMessages.add(Map.of("role", role, "content", content));
        }

        Map<String, Object> body = Map.of(
                "model", extractionModel,
                "messages", bodyMessages,
                "max_tokens", 2048,
                "temperature", 0.2
        );

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .post(RequestBody.create(MAPPER.writeValueAsString(body), JSON))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Extraction API error: " + response.code() + " " + response.body().string());
            }
            var tree = MAPPER.readTree(response.body().string());
            String content = tree.path("choices").get(0).path("message").path("content").asText();

            // Strip markdown fences if present
            if (content.startsWith("```")) {
                int idx = content.indexOf('\n');
                if (idx != -1) content = content.substring(idx + 1);
                if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
                content = content.trim();
            }
            return content;
        }
    }

    // ---- Routing -----------------------------------------------------------

    private void routeFacts(List<ExtractedKnowledge.KnowledgeItem> items, ExtractionResult result) {
        for (var item : filterBy(items, policy.getMemoryConfidenceThreshold())) {
            boolean ok = memoryManager.addMemory(item.getContent());
            if (ok) {
                result.addMemorySaved(item.getContent());
                result.getInsights().add(item.getContent());
            }
        }
    }

    private void routeUserProfile(List<ExtractedKnowledge.KnowledgeItem> items, ExtractionResult result) {
        for (var item : filterBy(items, policy.getUserProfileConfidenceThreshold())) {
            boolean ok = memoryManager.addUser(item.getContent());
            if (ok) {
                result.getInsights().add("[PROFILE] " + item.getContent());
            }
        }
    }

    private void routeSkillHints(List<ExtractedKnowledge.SkillHint> hints, ExtractionResult result) {
        for (var hint : hints) {
            if (hint.getConfidence() < policy.getSkillConfidenceThreshold()) continue;
            if (hint.getName() == null || hint.getName().isBlank()) continue;
            String body = hint.getContent() != null && !hint.getContent().isBlank()
                    ? hint.getContent()
                    : hint.getDescription();
            if (body != null && body.length() > 80) {
                result.setSkillCandidate(body);
                logger.info("Skill candidate detected: {}", hint.getName());
            }
        }
    }

    private void routeAntiPatterns(List<ExtractedKnowledge.KnowledgeItem> items, ExtractionResult result) {
        // Anti-patterns go into memory as facts with a prefix for now.
        for (var item : filterBy(items, policy.getMemoryConfidenceThreshold())) {
            String prefixed = "[ANTI-PATTERN] " + item.getContent();
            memoryManager.addMemory(prefixed);
            result.getInsights().add(prefixed);
        }
    }

    // ---- Utilities ---------------------------------------------------------

    static List<ExtractedKnowledge.KnowledgeItem> filterBy(List<ExtractedKnowledge.KnowledgeItem> items, double threshold) {
        if (items == null || items.isEmpty()) return List.of();
        return items.stream()
                .filter(i -> i.getConfidence() >= threshold)
                .collect(Collectors.toList());
    }

    private String formatConversation(List<ModelMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size() && sb.length() < 16_000; i++) {
            ModelMessage msg = messages.get(i);
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                if (sb.length() > 0) sb.append("\n---\n");
                sb.append(msg.getRole().toUpperCase()).append(": ").append(msg.getContent());
            }
        }
        return sb.toString();
    }

    private ExtractionResult legacyFallback(List<ModelMessage> messages) {
        logger.info("Falling back to heuristic extraction");
        ExtractionResult result = new ExtractionResult();
        List<String> insights = legacyExtractInsights(messages);
        result.setInsights(insights);
        for (String insight : insights) {
            if (shouldSaveToMemory(insight)) {
                memoryManager.addMemory(insight);
                result.addMemorySaved(insight);
            }
        }
        Optional<String> skill = legacyExtractSkillPattern(messages);
        skill.ifPresent(result::setSkillCandidate);
        return result;
    }

    private List<String> legacyExtractInsights(List<ModelMessage> messages) {
        // Simple heuristic: look for explicit declarations of preference / decision / config
        List<String> insights = new ArrayList<>();
        for (ModelMessage msg : messages) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                String c = msg.getContent();
                if (c.length() > 20 && c.length() < 500) {
                    if (c.matches(".*(?i)(i am|i'm|my name|i use|i prefer|i like|please call|config|set up|project|remember|important).*")) {
                        insights.add(c);
                    }
                }
            }
        }
        return insights.stream().limit(policy.getMaxItemsPerBucket()).collect(Collectors.toList());
    }

    private Optional<String> legacyExtractSkillPattern(List<ModelMessage> messages) {
        if (!isSkillWorthyWorkflow(messages)) return Optional.empty();
        int toolCount = 0;
        for (ModelMessage msg : messages) {
            if (msg.getToolCalls() != null) toolCount += msg.getToolCalls().size();
        }
        String suggestion = "## Auto-detected workflow (" + toolCount + " tool calls)\n\n"
                + "Review conversation for reusable pattern.\n";
        return Optional.of(suggestion);
    }

    private boolean isSkillWorthyWorkflow(List<ModelMessage> messages) {
        int toolCalls = 0;
        int userMessages = 0;
        for (ModelMessage msg : messages) {
            if ("user".equals(msg.getRole())) userMessages++;
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                toolCalls += msg.getToolCalls().size();
            }
        }
        return toolCalls >= 3 && userMessages >= 2 && messages.size() >= 6;
    }

    private boolean shouldSaveToMemory(String insight) {
        if (insight.length() < 30) return false;
        String lower = insight.toLowerCase();
        if (lower.contains("summary") || lower.contains("overview")) return false;
        return lower.contains("user") || lower.contains("prefer")
                || lower.contains("config") || lower.contains("project")
                || lower.contains("important") || lower.contains("note");
    }

    // ========================================================================
    // EXTRACTION RESULT (backward-compatible DTO)
    // ========================================================================

    public static class ExtractionResult {
        private List<String> insights = new ArrayList<>();
        private List<String> memoriesSaved = new ArrayList<>();
        private String skillCandidate;

        public static ExtractionResult empty() { return new ExtractionResult(); }

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
