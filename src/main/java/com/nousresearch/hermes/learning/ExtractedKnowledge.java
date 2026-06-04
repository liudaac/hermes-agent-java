package com.nousresearch.hermes.learning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured knowledge extracted from a conversation by an LLM.
 *
 * <p>The extraction prompt asks the model to emit JSON conforming to this shape,
 * so downstream code can apply confidence filtering and route each item to the
 * appropriate sink (long-term memory, user profile, skill candidates, anti-patterns).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtractedKnowledge {

    /** Stable facts worth storing in MEMORY.md (project setup, decisions, etc.). */
    @JsonProperty("facts")
    private List<KnowledgeItem> facts = new ArrayList<>();

    /** Updates to the user profile (preferences, name, timezone, calling style). */
    @JsonProperty("user_profile")
    private List<KnowledgeItem> userProfile = new ArrayList<>();

    /** Workflows that look reusable and could become skills. */
    @JsonProperty("skill_hints")
    private List<SkillHint> skillHints = new ArrayList<>();

    /** Mistakes / failure modes worth remembering so we avoid them next time. */
    @JsonProperty("anti_patterns")
    private List<KnowledgeItem> antiPatterns = new ArrayList<>();

    // --- getters / setters ---

    public List<KnowledgeItem> getFacts() { return facts; }
    public void setFacts(List<KnowledgeItem> facts) {
        this.facts = facts != null ? facts : new ArrayList<>();
    }

    public List<KnowledgeItem> getUserProfile() { return userProfile; }
    public void setUserProfile(List<KnowledgeItem> userProfile) {
        this.userProfile = userProfile != null ? userProfile : new ArrayList<>();
    }

    public List<SkillHint> getSkillHints() { return skillHints; }
    public void setSkillHints(List<SkillHint> skillHints) {
        this.skillHints = skillHints != null ? skillHints : new ArrayList<>();
    }

    public List<KnowledgeItem> getAntiPatterns() { return antiPatterns; }
    public void setAntiPatterns(List<KnowledgeItem> antiPatterns) {
        this.antiPatterns = antiPatterns != null ? antiPatterns : new ArrayList<>();
    }

    public boolean isEmpty() {
        return facts.isEmpty() && userProfile.isEmpty()
            && skillHints.isEmpty() && antiPatterns.isEmpty();
    }

    public int totalItems() {
        return facts.size() + userProfile.size() + skillHints.size() + antiPatterns.size();
    }

    // ---------------------------------------------------------------
    // Item types
    // ---------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KnowledgeItem {
        @JsonProperty("content")
        private String content;

        /** Model self-reported confidence in [0.0, 1.0]. Items below threshold are dropped. */
        @JsonProperty("confidence")
        private double confidence = 0.5;

        /** Free-form tags (e.g. "preference", "config", "project:hermes"). */
        @JsonProperty("tags")
        private List<String> tags = new ArrayList<>();

        public KnowledgeItem() {}

        public KnowledgeItem(String content, double confidence) {
            this.content = content;
            this.confidence = confidence;
        }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }

        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) {
            this.tags = tags != null ? tags : new ArrayList<>();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillHint {
        @JsonProperty("name")
        private String name;

        @JsonProperty("description")
        private String description;

        /** Suggested skill body (markdown) - may be empty if model only flagged it. */
        @JsonProperty("content")
        private String content;

        @JsonProperty("tags")
        private List<String> tags = new ArrayList<>();

        @JsonProperty("confidence")
        private double confidence = 0.5;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) {
            this.tags = tags != null ? tags : new ArrayList<>();
        }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
    }
}
