package com.nousresearch.hermes.space;

import java.util.*;

/**
 * Space-level knowledge base entry.
 */
public record KnowledgeEntry(
    String id,
    String title,
    String content,
    String category,         // "sop" | "faq" | "domain" | "experience"
    List<String> tags,
    String authorId,         // userId of creator
    long createdAt,
    long updatedAt
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("content", content);
        m.put("category", category);
        m.put("tags", tags);
        m.put("authorId", authorId);
        m.put("createdAt", createdAt);
        m.put("updatedAt", updatedAt);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static KnowledgeEntry fromMap(Map<String, Object> m) {
        return new KnowledgeEntry(
            (String) m.get("id"),
            (String) m.get("title"),
            (String) m.get("content"),
            (String) m.getOrDefault("category", "domain"),
            (List<String>) m.getOrDefault("tags", List.of()),
            (String) m.get("authorId"),
            ((Number) m.getOrDefault("createdAt", 0L)).longValue(),
            ((Number) m.getOrDefault("updatedAt", 0L)).longValue()
        );
    }
}
