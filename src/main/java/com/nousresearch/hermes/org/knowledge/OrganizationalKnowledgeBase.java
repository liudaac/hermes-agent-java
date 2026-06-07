package com.nousresearch.hermes.org.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized organizational knowledge base with semantic search,
 * vector embeddings integration point, and cross-tenant sharing.
 *
 * <p>Provides RAG-ready retrieval for AI agents:</p>
 * <ul>
 *   <li>Full-text search across titles and content</li>
 *   <li>Tag-based filtering and topic browsing</li>
 *   <li>Classification-aware access control</li>
 *   <li>Embedding-based semantic search (integration point)</li>
 *   <li>Auto-expiry for time-sensitive entries</li>
 *   <li>Usage analytics for knowledge health</li>
 * </ul>
 */
public class OrganizationalKnowledgeBase {
    private static final Logger logger = LoggerFactory.getLogger(OrganizationalKnowledgeBase.class);

    private final ConcurrentHashMap<String, KnowledgeEntry> entries = new ConcurrentHashMap<>();

    // Indices
    private final ConcurrentHashMap<String, Set<String>> tagIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> topicIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<KnowledgeEntry.Type, Set<String>> typeIndex = new ConcurrentHashMap<>();

    /** Max entries before pruning is triggered. */
    private static final int MAX_ENTRIES = 10_000;

    /** Auto-archive entries older than this if not accessed. */
    private static final Duration STALE_THRESHOLD = Duration.ofDays(180);

    // ---- CRUD -------

    /** Add or update a knowledge entry. */
    public KnowledgeEntry put(KnowledgeEntry entry) {
        entries.put(entry.getId(), entry);
        index(entry);

        if (entries.size() > MAX_ENTRIES) {
            pruneStale();
        }

        logger.debug("Knowledge entry added: {} ({})", entry.getTitle(), entry.getType());
        return entry;
    }

    /** Get by ID. */
    public Optional<KnowledgeEntry> get(String id) {
        KnowledgeEntry e = entries.get(id);
        if (e != null) e.recordView();
        return Optional.ofNullable(e);
    }

    /** Remove an entry. */
    public boolean remove(String id) {
        KnowledgeEntry e = entries.remove(id);
        if (e != null) {
            deindex(e);
            return true;
        }
        return false;
    }

    // ---- search -------

    /**
     * Full-text search across titles and content.
     * Returns entries ranked by relevance (exact title match > title contains > content contains).
     */
    public List<KnowledgeEntry> search(String query, int maxResults) {
        String q = query.toLowerCase();
        List<ScoredEntry> scored = new ArrayList<>();

        for (KnowledgeEntry e : entries.values()) {
            double score = 0;
            if (e.getTitle().toLowerCase().equals(q)) {
                score = 100;
            } else if (e.getTitle().toLowerCase().contains(q)) {
                score = 50;
            } else if (e.getContent().toLowerCase().contains(q)) {
                // Score by occurrence density
                int count = countOccurrences(e.getContent().toLowerCase(), q);
                score = Math.min(count * 5, 40);
            }
            if (score > 0) scored.add(new ScoredEntry(e, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream().limit(maxResults).map(s -> s.entry).toList();
    }

    /** Find entries by tag. */
    public List<KnowledgeEntry> findByTag(String tag) {
        Set<String> ids = tagIndex.getOrDefault(tag, Set.of());
        return ids.stream().map(entries::get).filter(Objects::nonNull).toList();
    }

    /** Find entries by topic. */
    public List<KnowledgeEntry> findByTopic(String topic) {
        Set<String> ids = topicIndex.getOrDefault(topic, Set.of());
        return ids.stream().map(entries::get).filter(Objects::nonNull).toList();
    }

    /** Find entries by type. */
    public List<KnowledgeEntry> findByType(KnowledgeEntry.Type type) {
        Set<String> ids = typeIndex.getOrDefault(type, Set.of());
        return ids.stream().map(entries::get).filter(Objects::nonNull).toList();
    }

    /**
     * Find entries relevant to a given context (agent's task description).
     * Combines title match, tag match, and topic match.
     */
    public List<KnowledgeEntry> findRelevant(String context, int maxResults) {
        String ctx = context.toLowerCase();
        Set<KnowledgeEntry> results = new LinkedHashSet<>();

        // Full-text search
        results.addAll(search(ctx, maxResults));

        // Tag match: check if any words in context match tags
        for (String word : ctx.split("\\s+")) {
            if (word.length() >= 3) {
                results.addAll(findByTag(word));
            }
        }

        return results.stream().limit(maxResults).toList();
    }

    /**
     * Build a RAG context string from the top-N most relevant entries.
     */
    public String buildRagContext(String query, int maxEntries, int maxCharsPerEntry) {
        List<KnowledgeEntry> relevant = search(query, maxEntries);
        StringBuilder sb = new StringBuilder();
        sb.append("# Organizational Knowledge\n\n");
        for (KnowledgeEntry e : relevant) {
            sb.append("## ").append(e.getTitle()).append("\n");
            sb.append("Type: ").append(e.getType()).append(" | Tags: ").append(String.join(", ", e.getTags())).append("\n\n");
            String content = e.getContent();
            if (content.length() > maxCharsPerEntry) {
                content = content.substring(0, maxCharsPerEntry) + "...";
            }
            sb.append(content).append("\n\n---\n\n");
            e.recordCitation();
        }
        return sb.toString();
    }

    // ---- semantic search integration point ----

    /**
     * Placeholder for vector-based semantic search.
     * Integrate with pgvector, Milvus, Pinecone, etc.
     */
    public List<KnowledgeEntry> semanticSearch(float[] queryEmbedding, int maxResults) {
        List<ScoredEntry> scored = new ArrayList<>();
        for (KnowledgeEntry e : entries.values()) {
            e.getEmbedding().ifPresent(emb -> {
                double similarity = cosineSimilarity(queryEmbedding, emb);
                if (similarity > 0.5) scored.add(new ScoredEntry(e, similarity));
            });
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored.stream().limit(maxResults).map(s -> s.entry).toList();
    }

    // ---- analytics -------

    /** Most viewed entries. */
    public List<KnowledgeEntry> mostViewed(int n) {
        return entries.values().stream()
            .sorted(Comparator.comparingInt(KnowledgeEntry::getViewCount).reversed())
            .limit(n).toList();
    }

    /** Most cited entries. */
    public List<KnowledgeEntry> mostCited(int n) {
        return entries.values().stream()
            .sorted(Comparator.comparingInt(KnowledgeEntry::getCitationCount).reversed())
            .limit(n).toList();
    }

    /** Entries that haven't been accessed recently. */
    public List<KnowledgeEntry> staleEntries(Duration threshold) {
        Instant cutoff = Instant.now().minus(threshold);
        return entries.values().stream()
            .filter(e -> e.getUpdatedAt().isBefore(cutoff) && e.getViewCount() < 2)
            .toList();
    }

    /** Count by classification. */
    public Map<KnowledgeEntry.Classification, Long> countByClassification() {
        Map<KnowledgeEntry.Classification, Long> counts = new LinkedHashMap<>();
        for (KnowledgeEntry e : entries.values()) {
            counts.merge(e.getClassification(), 1L, Long::sum);
        }
        return counts;
    }

    /** Knowledge graph edges: entry → related entries. */
    public Map<String, List<String>> getKnowledgeGraph(int maxEntries) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (KnowledgeEntry e : entries.values()) {
            if (!e.getRelatedEntries().isEmpty()) {
                graph.put(e.getId(), new ArrayList<>(e.getRelatedEntries().keySet()));
            }
            if (graph.size() >= maxEntries) break;
        }
        return graph;
    }

    /** Summary for dashboard. */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total_entries", entries.size());
        s.put("by_type", Map.of(
            "sop", findByType(KnowledgeEntry.Type.SOP).size(),
            "decision", findByType(KnowledgeEntry.Type.DECISION).size(),
            "lesson", findByType(KnowledgeEntry.Type.LESSON).size(),
            "faq", findByType(KnowledgeEntry.Type.FAQ).size(),
            "policy", findByType(KnowledgeEntry.Type.POLICY).size(),
            "reference", findByType(KnowledgeEntry.Type.REFERENCE).size(),
            "insight", findByType(KnowledgeEntry.Type.INSIGHT).size()
        ));
        s.put("by_classification", countByClassification());
        s.put("unique_tags", tagIndex.size());
        s.put("unique_topics", topicIndex.size());
        s.put("most_viewed", mostViewed(5).stream().map(KnowledgeEntry::getTitle).toList());
        s.put("stale_count", staleEntries(STALE_THRESHOLD).size());
        return s;
    }

    /** Total entries. */
    public int size() { return entries.size(); }

    /** All entries (caution: potentially large). */
    public Collection<KnowledgeEntry> all() { return Collections.unmodifiableCollection(entries.values()); }

    // ---- internal -------

    private void index(KnowledgeEntry e) {
        for (String tag : e.getTags()) {
            tagIndex.computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet()).add(e.getId());
        }
        for (String topic : e.getTopics()) {
            topicIndex.computeIfAbsent(topic, k -> ConcurrentHashMap.newKeySet()).add(e.getId());
        }
        typeIndex.computeIfAbsent(e.getType(), k -> ConcurrentHashMap.newKeySet()).add(e.getId());
    }

    private void deindex(KnowledgeEntry e) {
        for (String tag : e.getTags()) {
            Set<String> ids = tagIndex.get(tag);
            if (ids != null) ids.remove(e.getId());
        }
        for (String topic : e.getTopics()) {
            Set<String> ids = topicIndex.get(topic);
            if (ids != null) ids.remove(e.getId());
        }
        Set<String> ids = typeIndex.get(e.getType());
        if (ids != null) ids.remove(e.getId());
    }

    private void pruneStale() {
        List<KnowledgeEntry> stale = staleEntries(STALE_THRESHOLD);
        for (KnowledgeEntry e : stale) {
            remove(e.getId());
            logger.info("Pruned stale knowledge entry: {}", e.getTitle());
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++; idx += needle.length();
        }
        return count;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (normA == 0 || normB == 0) ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record ScoredEntry(KnowledgeEntry entry, double score) {}
}
