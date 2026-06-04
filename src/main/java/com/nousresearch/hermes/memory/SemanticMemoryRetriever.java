package com.nousresearch.hermes.memory;

import com.nousresearch.hermes.model.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Semantic (embedding-based) memory retriever.
 *
 * <p>Bridges the {@link ModelClient#createEmbedding(String)} API with the
 * {@link EmbeddingStore} to provide vector search over memory entries.
 * Falls back to {@link MemoryRetriever} (BM25-lite) if embeddings are
 * unavailable or the corpus is too small for vectors to matter.</p>
 */
public class SemanticMemoryRetriever {

    private static final Logger logger = LoggerFactory.getLogger(SemanticMemoryRetriever.class);

    private final ModelClient modelClient;
    private final EmbeddingStore store;
    private final MemoryRetriever fallback;

    public SemanticMemoryRetriever(ModelClient modelClient, Path dbPath, String tenantId,
                                   MemoryManager memoryManager) throws Exception {
        this.modelClient = modelClient;
        this.store = new EmbeddingStore(dbPath, tenantId);
        this.fallback = new MemoryRetriever(memoryManager);
    }

    /**
     * Index a memory entry into the vector store.
     *
     * @param content  the text to embed and store
     * @param category "memory" | "user" | "lesson" | "anti_pattern"
     */
    public void index(String content, String category) {
        try {
            float[] emb = modelClient.createEmbedding(content);
            store.insert(content, emb, category, List.of());
        } catch (Exception e) {
            logger.warn("Failed to index memory entry for semantic search: {}", e.getMessage());
        }
    }

    /**
     * Semantic search: embed the query, then find closest entries in the store.
     *
     * @param query  user message / query text
     * @param topK   max results
     * @return ranked results; empty if embedding fails or store is empty
     */
    public List<EmbeddingStore.SearchResult> retrieve(String query, int topK) {
        try {
            if (store.count() == 0) return List.of();
            float[] qEmb = modelClient.createEmbedding(query);
            return store.search(qEmb, topK);
        } catch (Exception e) {
            logger.warn("Semantic retrieval failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Hybrid search: run both BM25 and vector, then merge by reciprocal rank fusion.
     *
     * @param query user message
     * @param topK  max final results
     * @return unified ranked list of content strings
     */
    public List<String> retrieveHybrid(String query, int topK) {
        // BM25 results
        List<MemoryRetriever.RetrievedEntry> lexical = fallback.retrieve(query, topK * 2);
        // Vector results
        List<EmbeddingStore.SearchResult> semantic = retrieve(query, topK * 2);

        // Reciprocal Rank Fusion (RRF) — k=60
        final double RRF_K = 60.0;
        java.util.Map<String, Double> scores = new java.util.HashMap<>();

        for (int i = 0; i < lexical.size(); i++) {
            String c = lexical.get(i).content;
            scores.merge(c, 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < semantic.size(); i++) {
            String c = semantic.get(i).content();
            scores.merge(c, 1.0 / (RRF_K + i + 1), Double::sum);
        }

        return scores.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(topK)
            .map(java.util.Map.Entry::getKey)
            .toList();
    }

    public EmbeddingStore getStore() { return store; }
    public MemoryRetriever getFallback() { return fallback; }
}
