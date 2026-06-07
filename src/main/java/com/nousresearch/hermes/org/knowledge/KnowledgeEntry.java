package com.nousresearch.hermes.org.knowledge;

import java.time.Instant;
import java.util.*;

/**
 * A single knowledge entry in the organizational knowledge base.
 *
 * <p>Entries are tagged, classified, and versioned. They form the nodes
 * of an organizational knowledge graph that persists across agent sessions
 * and tenant boundaries.</p>
 */
public class KnowledgeEntry {

    public enum Type {
        /** Standard operating procedure */
        SOP,
        /** Technical decision record */
        DECISION,
        /** Post-mortem / lessons learned */
        LESSON,
        /** FAQ / common knowledge */
        FAQ,
        /** Policy document */
        POLICY,
        /** Reference material */
        REFERENCE,
        /** Agent-generated insight */
        INSIGHT
    }

    public enum Classification { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }

    private final String id;
    private final Type type;
    private final Classification classification;
    private final String title;
    private String content;
    private final String author;       // agent ID or human user ID
    private final Instant createdAt;
    private Instant updatedAt;
    private int version = 1;

    // Semantic tagging
    private final Set<String> tags = new LinkedHashSet<>();
    private final Set<String> topics = new LinkedHashSet<>();

    // Relationships to other entries
    private final Map<String, String> relatedEntries = new LinkedHashMap<>(); // entryId → relationship type

    // Full-text search embedding placeholder (vector DB integration point)
    private float[] embedding;

    // Usage metrics
    private volatile int viewCount;
    private volatile int citationCount;
    private volatile double relevanceScore;

    public KnowledgeEntry(String id, Type type, Classification classification, String title, String content, String author) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.classification = classification != null ? classification : Classification.INTERNAL;
        this.title = Objects.requireNonNull(title, "title");
        this.content = Objects.requireNonNull(content, "content");
        this.author = author;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Update content, incrementing the version. */
    public void update(String newContent) {
        this.content = newContent;
        this.version++;
        this.updatedAt = Instant.now();
    }

    public KnowledgeEntry tag(String... t) { Collections.addAll(tags, t); return this; }
    public KnowledgeEntry topics(String... t) { Collections.addAll(topics, t); return this; }
    public KnowledgeEntry related(String entryId, String relationship) { relatedEntries.put(entryId, relationship); return this; }
    public KnowledgeEntry embedding(float[] emb) { this.embedding = emb; return this; }

    public void recordView() { viewCount++; }
    public void recordCitation() { citationCount++; }

    // ---- getters -------
    public String getId() { return id; }
    public Type getType() { return type; }
    public Classification getClassification() { return classification; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getAuthor() { return author; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
    public Set<String> getTags() { return Collections.unmodifiableSet(tags); }
    public Set<String> getTopics() { return Collections.unmodifiableSet(topics); }
    public Map<String, String> getRelatedEntries() { return Collections.unmodifiableMap(relatedEntries); }
    public Optional<float[]> getEmbedding() { return Optional.ofNullable(embedding); }
    public int getViewCount() { return viewCount; }
    public int getCitationCount() { return citationCount; }
    public double getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(double s) { this.relevanceScore = s; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type.name());
        m.put("classification", classification.name());
        m.put("title", title);
        m.put("content", truncate(content, 200));
        m.put("author", author);
        m.put("created_at", createdAt.toString());
        m.put("updated_at", updatedAt.toString());
        m.put("version", version);
        m.put("tags", tags);
        m.put("topics", topics);
        m.put("view_count", viewCount);
        m.put("citation_count", citationCount);
        m.put("relevance_score", relevanceScore);
        return m;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}