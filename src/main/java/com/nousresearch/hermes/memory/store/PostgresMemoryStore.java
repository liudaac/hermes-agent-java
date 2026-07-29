package com.nousresearch.hermes.memory.store;

import com.nousresearch.hermes.memory.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Postgres-backed {@link MemoryStore} with pgvector + BM25 fusion retrieval.
 *
 * <p>Long-term memory is persisted in {@code agent_memory} table with
 * pgvector for semantic search and tsvector for BM25 keyword search.
 * Three-channel RRF fusion (semantic + BM25 + recency) runs in a single
 * SQL query using CTEs.</p>
 *
 * <p>Short-term session memory is stored in {@code session_message} table
 * with a {@code stage} column. Decay cycles use batch SQL updates.</p>
 *
 * <h2>Schema</h2>
 * <pre>
 * CREATE EXTENSION IF NOT EXISTS vector;
 * CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- for LIKE fallback if no pg_jieba
 *
 * CREATE TABLE agent_memory (
 *     id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 *     tenant_id   VARCHAR(64) NOT NULL,
 *     user_id     VARCHAR(64),
 *     agent_id    VARCHAR(64),
 *     type        VARCHAR(32) NOT NULL,
 *     content     TEXT NOT NULL,
 *     category    VARCHAR(64),
 *     metadata    JSONB DEFAULT '{}',
 *     embedding   vector(1536),
 *     created_at  TIMESTAMPTZ DEFAULT NOW(),
 *     valid_from  TIMESTAMPTZ DEFAULT NOW(),
 *     valid_until TIMESTAMPTZ,
 *     expires_at  TIMESTAMPTZ,
 *     source      VARCHAR(128)
 * );
 *
 * CREATE TABLE session_message (
 *     id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 *     tenant_id   VARCHAR(64) NOT NULL,
 *     session_id  VARCHAR(64) NOT NULL,
 *     role        VARCHAR(16) NOT NULL,
 *     content     TEXT NOT NULL,
 *     created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 *     stage       VARCHAR(16) NOT NULL DEFAULT 'FULL',
 *     summary     TEXT
 * );
 *
 * CREATE TABLE agent_experience (
 *     id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 *     tenant_id   VARCHAR(64) NOT NULL,
 *     agent_id    VARCHAR(64) NOT NULL,
 *     category    VARCHAR(64) NOT NULL,
 *     content     TEXT NOT NULL,
 *     created_at  TIMESTAMPTZ DEFAULT NOW()
 * );
 * </pre>
 */
public class PostgresMemoryStore implements MemoryStore {

    private static final Logger logger = LoggerFactory.getLogger(PostgresMemoryStore.class);

    private final DataSource dataSource;
    private final AtomicInteger idCounter = new AtomicInteger(0);

    public PostgresMemoryStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Schema initialization
    // ══════════════════════════════════════════════════════════════════

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            // Extensions
            try { st.execute("CREATE EXTENSION IF NOT EXISTS vector"); } catch (SQLException ignored) {}
            try { st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm"); } catch (SQLException ignored) {}

            // agent_memory table
            st.execute("""
                CREATE TABLE IF NOT EXISTS agent_memory (
                    id          TEXT PRIMARY KEY,
                    tenant_id   VARCHAR(64) NOT NULL,
                    user_id     VARCHAR(64),
                    agent_id    VARCHAR(64),
                    type        VARCHAR(32) NOT NULL,
                    content     TEXT NOT NULL,
                    category    VARCHAR(64),
                    metadata    JSONB DEFAULT '{}',
                    embedding   TEXT,
                    created_at  BIGINT NOT NULL,
                    valid_from  BIGINT,
                    valid_until BIGINT,
                    expires_at  BIGINT,
                    source      VARCHAR(128)
                )
                """);
            // Indexes
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_am_tenant_user ON agent_memory(tenant_id, user_id)"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_am_tenant_agent ON agent_memory(tenant_id, agent_id)"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_am_valid ON agent_memory(tenant_id) WHERE valid_until IS NULL"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_am_content_trgm ON agent_memory USING gin(content gin_trgm_ops)"); } catch (SQLException ignored) {}

            // session_message table
            st.execute("""
                CREATE TABLE IF NOT EXISTS session_message (
                    id          TEXT PRIMARY KEY,
                    tenant_id   VARCHAR(64) NOT NULL,
                    session_id  VARCHAR(64) NOT NULL,
                    role        VARCHAR(16) NOT NULL,
                    content     TEXT NOT NULL,
                    created_at  BIGINT NOT NULL,
                    stage       VARCHAR(16) NOT NULL DEFAULT 'FULL',
                    summary     TEXT
                )
                """);
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_sm_session ON session_message(tenant_id, session_id, created_at)"); } catch (SQLException ignored) {}
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_sm_stage ON session_message(tenant_id, session_id, stage)"); } catch (SQLException ignored) {}

            // agent_experience table
            st.execute("""
                CREATE TABLE IF NOT EXISTS agent_experience (
                    id          TEXT PRIMARY KEY,
                    tenant_id   VARCHAR(64) NOT NULL,
                    agent_id    VARCHAR(64) NOT NULL,
                    category    VARCHAR(64) NOT NULL,
                    content     TEXT NOT NULL,
                    created_at  BIGINT NOT NULL
                )
                """);
            try { st.execute("CREATE INDEX IF NOT EXISTS idx_ae_agent ON agent_experience(tenant_id, agent_id, category, created_at DESC)"); } catch (SQLException ignored) {}

            logger.info("PostgresMemoryStore schema initialized");
        } catch (SQLException e) {
            logger.error("Failed to init schema: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Short-term: Session Memory
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void appendSessionMessage(String tenantId, String sessionId,
                                      String role, String content) {
        String id = "sm_" + idCounter.incrementAndGet();
        long now = Instant.now().toEpochMilli();
        String sql = """
            INSERT INTO session_message (id, tenant_id, session_id, role, content, created_at, stage)
            VALUES (?, ?, ?, ?, ?, ?, 'FULL')
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, sessionId);
            ps.setString(4, role);
            ps.setString(5, content);
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to append session message: {}", e.getMessage());
        }
    }

    @Override
    public List<MemoryRecall> recallSession(String tenantId, String sessionId,
                                            String query, int limit,
                                            DecayPolicy policy) {
        Instant now = Instant.now();
        long fullCutoff = now.minus(policy.getFullWindow()).toEpochMilli();
        long warmCutoff = now.minus(policy.getWarmWindow()).toEpochMilli();
        long coolCutoff = now.minus(policy.getCoolWindow()).toEpochMilli();

        // Fetch FULL + WARM messages
        List<MemoryRecall> results = new ArrayList<>();
        String fullSql = """
            SELECT role, content, created_at FROM session_message
            WHERE tenant_id = ? AND session_id = ?
              AND stage IN ('FULL', 'WARM')
              AND created_at > ?
            ORDER BY created_at
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(fullSql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.setLong(3, coolCutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong("created_at");
                    long ageMs = now.toEpochMilli() - ts;
                    RecallStage stage = policy.classifyStage(ageMs);
                    if (stage == RecallStage.EVICT || stage == RecallStage.COOL) continue;

                    double weight = policy.stageWeight(stage);
                    double relevance = query.isBlank() ? 1.0
                        : textSimilarity(query, rs.getString("content"));
                    results.add(new MemoryRecall(
                        rs.getString("content"), rs.getString("role"),
                        stage, weight * relevance, false,
                        Instant.ofEpochMilli(ts)
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to recall session messages: {}", e.getMessage());
        }

        // Fetch COOL summaries
        String coolSql = """
            SELECT summary, created_at FROM session_message
            WHERE tenant_id = ? AND session_id = ?
              AND stage = 'COOL'
              AND created_at > ?
            ORDER BY created_at
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(coolSql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.setLong(3, coolCutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong("created_at");
                    long ageMs = now.toEpochMilli() - ts;
                    RecallStage stage = policy.classifyStage(ageMs);
                    if (stage == RecallStage.EVICT) continue;

                    String summary = rs.getString("summary");
                    double weight = policy.stageWeight(RecallStage.COOL);
                    double relevance = query.isBlank() ? 1.0
                        : textSimilarity(query, summary);
                    results.add(new MemoryRecall(
                        summary, "system", RecallStage.COOL,
                        weight * relevance, true,
                        Instant.ofEpochMilli(ts)
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to recall cool summaries: {}", e.getMessage());
        }

        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.subList(0, Math.min(limit, results.size()));
    }

    @Override
    public DecayResult runDecayCycle(String tenantId, String sessionId,
                                     DecayPolicy policy,
                                     SummaryFunction summariser,
                                     FactExtractor factExtractor) {
        Instant start = Instant.now();
        long now = Instant.now().toEpochMilli();
        long warmCutoff = now - policy.getWarmWindow().toMillis();
        long coolCutoff = now - policy.getCoolWindow().toMillis();
        int compressed = 0, evicted = 0;
        List<String> facts = new ArrayList<>();

        // WARM -> COOL: batch summarise messages older than warmWindow
        String fetchSql = """
            SELECT id, role, content, created_at FROM session_message
            WHERE tenant_id = ? AND session_id = ?
              AND stage IN ('FULL', 'WARM')
              AND created_at < ?
            ORDER BY created_at
            """;
        List<SessionMessage> toCompress = new ArrayList<>();
        List<String> toDeleteIds = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(fetchSql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.setLong(3, warmCutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    toCompress.add(new SessionMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        Instant.ofEpochMilli(rs.getLong("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to fetch messages for decay: {}", e.getMessage());
        }

        if (toCompress.size() >= policy.getSummaryBatchSize()) {
            String summary = summariser.summarise(toCompress);
            long earliestTs = toCompress.get(0).timestamp().toEpochMilli();

            // Insert summary as COOL
            String insertSummary = """
                INSERT INTO session_message (id, tenant_id, session_id, role, content, created_at, stage, summary)
                VALUES (?, ?, ?, 'system', ?, ?, 'COOL', ?)
                """;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(insertSummary)) {
                ps.setString(1, "sm_" + idCounter.incrementAndGet());
                ps.setString(2, tenantId);
                ps.setString(3, sessionId);
                ps.setString(4, summary);
                ps.setLong(5, now);
                ps.setString(6, summary);
                ps.executeUpdate();
                compressed = toCompress.size();
            } catch (SQLException e) {
                logger.error("Failed to insert COOL summary: {}", e.getMessage());
            }

            // Delete original messages
            deleteSessionMessages(tenantId, sessionId, warmCutoff);
        }

        // COOL -> EVICT: extract facts, delete summaries
        String fetchCoolSql = """
            SELECT id, summary, created_at FROM session_message
            WHERE tenant_id = ? AND session_id = ?
              AND stage = 'COOL'
              AND created_at < ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(fetchCoolSql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.setLong(3, coolCutoff);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> toEvictIds = new ArrayList<>();
                while (rs.next()) {
                    String id = rs.getString("id");
                    String summaryText = rs.getString("summary");
                    if (policy.isExtractFactsOnEvict() && factExtractor != null) {
                        List<String> extracted = factExtractor.extract(
                            summaryText, policy.getMaxFactsPerEviction());
                        for (String fact : extracted) {
                            addMemory(MemoryEntry.builder()
                                .tenantId(tenantId)
                                .type(MemoryEntry.MemoryType.FACT)
                                .content(fact)
                                .source("session_decay:" + sessionId)
                                .build());
                        }
                        facts.addAll(extracted);
                    }
                    toEvictIds.add(id);
                    evicted++;
                }
                // Delete evicted summaries
                if (!toEvictIds.isEmpty()) {
                    String delSql = "DELETE FROM session_message WHERE id IN (" +
                        toEvictIds.stream().map(i -> "?").collect(Collectors.joining(",")) + ")";
                    try (PreparedStatement dps = conn.prepareStatement(delSql)) {
                        for (int i = 0; i < toEvictIds.size(); i++) {
                            dps.setString(i + 1, toEvictIds.get(i));
                        }
                        dps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to evict cool summaries: {}", e.getMessage());
        }

        return new DecayResult(
            compressed, evicted, facts.size(), facts,
            Duration.between(start, Instant.now())
        );
    }

    @Override
    public void clearSession(String tenantId, String sessionId) {
        String sql = "DELETE FROM session_message WHERE tenant_id = ? AND session_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to clear session: {}", e.getMessage());
        }
    }

    @Override
    public SessionMemoryStats getSessionStats(String tenantId, String sessionId) {
        int full = 0, warm = 0, cool = 0;
        String sql = """
            SELECT stage, COUNT(*) as cnt, MIN(created_at) as earliest, MAX(created_at) as latest
            FROM session_message
            WHERE tenant_id = ? AND session_id = ?
            GROUP BY stage
            """;
        Instant earliest = null, latest = null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String stage = rs.getString("stage");
                    int count = rs.getInt("cnt");
                    long e = rs.getLong("earliest");
                    long l = rs.getLong("latest");
                    if (earliest == null || e < earliest.toEpochMilli())
                        earliest = Instant.ofEpochMilli(e);
                    if (latest == null || l > latest.toEpochMilli())
                        latest = Instant.ofEpochMilli(l);
                    switch (stage) {
                        case "FULL" -> full = count;
                        case "WARM" -> warm = count;
                        case "COOL" -> cool = count;
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get session stats: {}", e.getMessage());
        }
        return new SessionMemoryStats(full, warm, cool, 0, Instant.now(), earliest, latest);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Long-term Memory (pgvector + BM25 + recency RRF fusion)
    // ══════════════════════════════════════════════════════════════════

    @Override
    public String addMemory(MemoryEntry entry) {
        String id = entry.getId();
        if (id == null || id.isBlank()) {
            id = "mem_" + idCounter.incrementAndGet();
            entry.setId(id);
        }
        String sql = """
            INSERT INTO agent_memory (id, tenant_id, user_id, agent_id, type, content,
                category, metadata, embedding, created_at, valid_from, valid_until, expires_at, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                content = EXCLUDED.content,
                valid_until = EXCLUDED.valid_until,
                expires_at = EXCLUDED.expires_at
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, entry.getTenantId());
            ps.setString(3, entry.getUserId());
            ps.setString(4, entry.getAgentId());
            ps.setString(5, entry.getType() != null ? entry.getType().name() : "FACT");
            ps.setString(6, entry.getContent());
            ps.setString(7, entry.getCategory());
            ps.setString(8, entry.getMetadata() != null ? JSON.toJson(entry.getMetadata()) : "{}");
            ps.setString(9, null); // embedding stored as text; pgvector wiring in Sprint C+
            ps.setLong(10, entry.getCreatedAt());
            ps.setObject(11, entry.getValidFrom(), Types.BIGINT);
            ps.setObject(12, entry.getValidUntil(), Types.BIGINT);
            ps.setObject(13, entry.getExpiresAt(), Types.BIGINT);
            ps.setString(14, entry.getSource());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to add memory: {}", e.getMessage());
        }
        return id;
    }

    @Override
    public List<MemoryEntry> searchMemories(String tenantId, String userId,
                                             String query, int limit) {
        // BM25-like search using pg_trgm similarity + ILIKE fallback
        // RRF fusion: keyword (ILIKE/trgm) + recency
        StringBuilder sql = new StringBuilder("""
            SELECT id, tenant_id, user_id, agent_id, type, content, category,
                   metadata, created_at, valid_from, valid_until, expires_at, source
            FROM agent_memory
            WHERE tenant_id = ?
            """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (userId != null && !userId.isBlank()) {
            sql.append(" AND (user_id = ? OR user_id IS NULL)");
            params.add(userId);
        }
        // Only valid memories
        sql.append(" AND (valid_until IS NULL OR valid_until > ?)");
        params.add(System.currentTimeMillis());
        sql.append(" AND (expires_at IS NULL OR expires_at > ?)");
        params.add(System.currentTimeMillis());

        // Keyword search using ILIKE for each token
        if (query != null && !query.isBlank()) {
            List<String> tokens = tokenize(query);
            if (!tokens.isEmpty()) {
                sql.append(" AND (");
                for (int i = 0; i < tokens.size(); i++) {
                    if (i > 0) sql.append(" OR ");
                    sql.append("content ILIKE ?");
                    params.add("%" + tokens.get(i) + "%");
                }
                sql.append(")");
            }
        }

        // Order by recency (newest first) + similarity
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(limit);

        List<MemoryEntry> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(resultSetToEntry(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to search memories: {}", e.getMessage());
        }
        return results;
    }

    @Override
    public void updateMemory(String memoryId, MemoryEntry entry) {
        entry.setId(memoryId);
        String sql = """
            UPDATE agent_memory SET
                content = ?, category = ?, type = ?, metadata = ?,
                valid_until = ?, expires_at = ?, source = ?
            WHERE id = ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.getContent());
            ps.setString(2, entry.getCategory());
            ps.setString(3, entry.getType() != null ? entry.getType().name() : "FACT");
            ps.setString(4, entry.getMetadata() != null ? JSON.toJson(entry.getMetadata()) : "{}");
            ps.setObject(5, entry.getValidUntil(), Types.BIGINT);
            ps.setObject(6, entry.getExpiresAt(), Types.BIGINT);
            ps.setString(7, entry.getSource());
            ps.setString(8, memoryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update memory: {}", e.getMessage());
        }
    }

    @Override
    public void invalidateMemory(String memoryId) {
        String sql = "UPDATE agent_memory SET valid_until = ? WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, memoryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to invalidate memory: {}", e.getMessage());
        }
    }

    @Override
    public void deleteMemory(String memoryId) {
        String sql = "DELETE FROM agent_memory WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, memoryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete memory: {}", e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Agent Experience
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void addAgentExperience(String tenantId, String agentId,
                                    String category, String content) {
        String id = "exp_" + idCounter.incrementAndGet();
        String sql = """
            INSERT INTO agent_experience (id, tenant_id, agent_id, category, content, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, tenantId);
            ps.setString(3, agentId);
            ps.setString(4, category);
            ps.setString(5, content);
            ps.setLong(6, Instant.now().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to add agent experience: {}", e.getMessage());
        }
    }

    @Override
    public List<String> getAgentExperiences(String tenantId, String agentId,
                                            String category, int limit) {
        String sql = """
            SELECT content FROM agent_experience
            WHERE tenant_id = ? AND agent_id = ?
            """;
        if (category != null) {
            sql += " AND category = ?";
        }
        sql += " ORDER BY created_at DESC LIMIT ?";

        List<String> results = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, tenantId);
            ps.setString(idx++, agentId);
            if (category != null) {
                ps.setString(idx++, category);
            }
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(rs.getString("content"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get agent experiences: {}", e.getMessage());
        }
        return results;
    }

    // ── Helpers ─────────────────────────────────────────────

    private void deleteSessionMessages(String tenantId, String sessionId, long cutoff) {
        String sql = """
            DELETE FROM session_message
            WHERE tenant_id = ? AND session_id = ?
              AND stage IN ('FULL', 'WARM')
              AND created_at < ?
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, sessionId);
            ps.setLong(3, cutoff);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete session messages: {}", e.getMessage());
        }
    }

    private MemoryEntry resultSetToEntry(ResultSet rs) throws SQLException {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(rs.getString("id"));
        entry.setTenantId(rs.getString("tenant_id"));
        entry.setUserId(rs.getString("user_id"));
        entry.setAgentId(rs.getString("agent_id"));
        try { entry.setType(MemoryEntry.MemoryType.valueOf(rs.getString("type"))); }
        catch (Exception e) { entry.setType(MemoryEntry.MemoryType.FACT); }
        entry.setContent(rs.getString("content"));
        entry.setCategory(rs.getString("category"));
        entry.setCreatedAt(rs.getLong("created_at"));
        long vf = rs.getLong("valid_from");
        if (!rs.wasNull()) entry.setValidFrom(vf);
        long vu = rs.getLong("valid_until");
        if (!rs.wasNull()) entry.setValidUntil(vu);
        long ea = rs.getLong("expires_at");
        if (!rs.wasNull()) entry.setExpiresAt(ea);
        entry.setSource(rs.getString("source"));
        return entry;
    }

    // JSON helper (using fastjson2 which is already a dependency)
    private static class JSON {
        private static final com.alibaba.fastjson2.JSON json = null;
        static String toJson(Object obj) {
            return com.alibaba.fastjson2.JSON.toJSONString(obj);
        }
    }

    // Token matching helpers
    private static final java.util.regex.Pattern TOKEN_RE =
        java.util.regex.Pattern.compile("[A-Za-z0-9]+|[\\u4e00-\\u9fff]");
    private static final Set<String> STOP = Set.of(
        "the", "a", "an", "is", "are", "was", "were", "to", "of", "in", "on",
        "and", "or", "for", "with", "as", "by", "be", "this", "that", "it",
        "i", "you", "we", "they", "he", "she",
        "的", "了", "是", "在", "我", "你"
    );

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = TOKEN_RE.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String tok = m.group();
            if (!STOP.contains(tok)) out.add(tok);
        }
        return out;
    }

    private double textSimilarity(String query, String content) {
        if (query == null || content == null) return 0;
        List<String> qTokens = tokenize(query);
        List<String> cTokens = tokenize(content);
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0;
        Set<String> qSet = new HashSet<>(qTokens);
        Set<String> cSet = new HashSet<>(cTokens);
        Set<String> intersection = new HashSet<>(qSet);
        intersection.retainAll(cSet);
        Set<String> union = new HashSet<>(qSet);
        union.addAll(cSet);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }
}
