package com.nousresearch.hermes.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingStoreTest {

    @TempDir Path tempDir;
    private EmbeddingStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new EmbeddingStore(tempDir.resolve("test.db"), "test_tenant");
    }

    @Test
    void insert_and_count() throws Exception {
        long id = store.insert("hello world", new float[]{0.1f, 0.2f, 0.3f}, "memory", List.of("a", "b"));
        assertTrue(id > 0);
        assertEquals(1, store.count());
    }

    @Test
    void search_by_cosine_similarity() throws Exception {
        store.insert("cat", new float[]{1.0f, 0.0f, 0.0f}, "memory", List.of());
        store.insert("dog", new float[]{0.9f, 0.1f, 0.0f}, "memory", List.of());
        store.insert("car", new float[]{0.0f, 1.0f, 0.0f}, "memory", List.of());

        float[] query = new float[]{0.95f, 0.05f, 0.0f}; // closer to cat/dog
        List<EmbeddingStore.SearchResult> results = store.search(query, 2);
        assertEquals(2, results.size());
        assertEquals("cat", results.get(0).content()); // highest similarity
    }

    @Test
    void category_filter_works() throws Exception {
        store.insert("user pref", new float[]{0.5f, 0.5f}, "user", List.of());
        store.insert("sys fact",  new float[]{0.5f, 0.5f}, "memory", List.of());

        float[] q = new float[]{0.5f, 0.5f};
        assertEquals(1, store.search(q, 10, "user").size());
        assertEquals(2, store.search(q, 10).size());
    }

    @Test
    void cosine_similarity_math() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 0.0f, 0.0f};
        float[] c = {0.0f, 1.0f, 0.0f};
        assertEquals(1.0, EmbeddingStore.cosineSimilarity(a, b), 1e-6);
        assertEquals(0.0, EmbeddingStore.cosineSimilarity(a, c), 1e-6);
    }
}
