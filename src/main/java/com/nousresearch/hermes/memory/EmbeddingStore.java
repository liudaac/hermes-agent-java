package com.nousresearch.hermes.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Lightweight embedding storage backed by SQLite.
 *
 * <p>Stores (content, embedding vector, metadata) in a local SQLite file.
 * Search uses cosine similarity via a linear scan — acceptable for corpora
 * up to a few thousand entries. When scale grows past that, swap this for
 * hnswlib-java or a vector database without changing the interface.</p>
 *
 * <p>Each tenant gets its own table (or table prefix) so isolation is
 * maintained without a separate database per tenant.</p>
 */
public class EmbeddingStore implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection conn;
    private final String tableName;

    public EmbeddingStore(Path dbPath, String tenantId) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        this.tableName = "embeddings_" + sanitize(tenantId);
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    content TEXT NOT NULL,
                    embedding BLOB NOT NULL,
                    category TEXT DEFAULT 'memory',
                    tags TEXT DEFAULT '[]',
                    created_at INTEGER DEFAULT (strftime('%%s','now'))
                )
                """.formatted(tableName));
            s.execute("CREATE INDEX IF NOT EXISTS idx_%s_cat ON %s(category)"
                .formatted(tableName, tableName));
        }
    }

    /**
     * Insert a document with its pre-computed embedding.
     *
     * @param content    the text
     * @param embedding  float[] from an embedding model
     * @param category   "memory" | "user" | "lesson" | "anti_pattern"
     * @param tags       optional tags for filtering
     * @return the generated row id
     */
    public long insert(String content, float[] embedding, String category, List<String> tags) throws SQLException {
        String sql = "INSERT INTO " + tableName + " (content, embedding, category, tags) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, content);
            ps.setBytes(2, floatsToBytes(embedding));
            ps.setString(3, category != null ? category : "memory");
            ps.setString(4, MAPPER.writeValueAsString(tags != null ? tags : List.of()));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception e) {
            throw new SQLException("Failed to insert embedding", e);
        }
    }

    /**
     * Semantic search: find the top-K entries whose embedding is closest to
     * the query embedding (cosine similarity).
     */
    public List<SearchResult> search(float[] queryEmbedding, int topK) throws SQLException {
        return search(queryEmbedding, topK, null);
    }

    public List<SearchResult> search(float[] queryEmbedding, int topK, String categoryFilter) throws SQLException {
        String sql = "SELECT id, content, embedding, category, tags FROM " + tableName
            + (categoryFilter != null ? " WHERE category = ?" : "");
        List<Scored> scored = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (categoryFilter != null) ps.setString(1, categoryFilter);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    float[] emb = bytesToFloats(rs.getBytes("embedding"));
                    double sim = cosineSimilarity(queryEmbedding, emb);
                    if (sim > 0.55) { // hard floor — avoids noise
                        scored.add(new Scored(
                            rs.getLong("id"),
                            rs.getString("content"),
                            rs.getString("category"),
                            rs.getString("tags"),
                            sim
                        ));
                    }
                }
            }
        }

        scored.sort((a, b) -> Double.compare(b.sim, a.sim));
        return scored.stream()
            .limit(topK)
            .map(s -> new SearchResult(s.id, s.content, s.category, parseTags(s.tags), s.sim))
            .toList();
    }

    public int count() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    // ------------------------------------------------------------------

    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return -1;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0 : dot / denom;
    }

    private static byte[] floatsToBytes(float[] f) {
        byte[] b = new byte[f.length * 4];
        for (int i = 0; i < f.length; i++) {
            int bits = Float.floatToIntBits(f[i]);
            b[i * 4]     = (byte) (bits >> 24);
            b[i * 4 + 1] = (byte) (bits >> 16);
            b[i * 4 + 2] = (byte) (bits >> 8);
            b[i * 4 + 3] = (byte) bits;
        }
        return b;
    }

    private static float[] bytesToFloats(byte[] b) {
        float[] f = new float[b.length / 4];
        for (int i = 0; i < f.length; i++) {
            int bits = ((b[i * 4]     & 0xFF) << 24)
                     | ((b[i * 4 + 1] & 0xFF) << 16)
                     | ((b[i * 4 + 2] & 0xFF) << 8)
                     |  (b[i * 4 + 3] & 0xFF);
            f[i] = Float.intBitsToFloat(bits);
        }
        return f;
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private List<String> parseTags(String json) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private record Scored(long id, String content, String category, String tags, double sim) {}

    public record SearchResult(long id, String content, String category, List<String> tags, double similarity) {}
}
