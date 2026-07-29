package com.nousresearch.hermes.memory.store;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * In-memory {@link MemoryStore} implementation. Zero external dependencies.
 *
 * <p>Used by default in LOCAL mode and as a fallback in CLUSTER mode when
 * Redis is unavailable. Suitable for development and single-instance
 * deployments.</p>
 *
 * <h2>Short-term storage layout</h2>
 * <pre>
 *   sessions: ConcurrentHashMap&lt;sessionKey, SessionBucket&gt;
 *
 *   SessionBucket {
 *     fullMessages:   ConcurrentLinkedDeque&lt;TimestampedMessage&gt;  // FULL + WARM
 *     coolSummaries:  ConcurrentLinkedDeque&lt;TimestampedSummary&gt;  // COOL
 *     evictedCount:   AtomicInteger
 *     lastDecayRun:   volatile Instant
 *   }
 * </pre>
 *
 * <h2>Long-term storage layout</h2>
 * <pre>
 *   longTermMemories: ConcurrentHashMap&lt;memoryId, MemoryEntry&gt;
 *   agentExperiences: ConcurrentHashMap&lt;agentKey, ConcurrentLinkedQueue&lt;Experience&gt;&gt;
 * </pre>
 *
 * <p>BM25 retrieval uses an in-memory inverted index built on-the-fly
 * (corpus is typically &lt; 1000 entries). Semantic retrieval uses
 * cosine similarity over float[] embeddings when available, otherwise
 * falls back to keyword overlap. RRF fusion combines both channels
 * with a recency signal.</p>
 */
public class LocalMemoryStore implements MemoryStore {

    // ── Keys ────────────────────────────────────────────────

    private static String sessionKey(String tenantId, String sessionId) {
        return tenantId + ":" + sessionId;
    }

    private static String agentKey(String tenantId, String agentId) {
        return tenantId + ":" + agentId;
    }

    // ── Internal data structures ───────────────────────────

    private record TimestampedMessage(String role, String content, Instant timestamp) {}

    private record TimestampedSummary(String summary, Instant generatedAt,
                                       Instant originalTime, int messageCount) {}

    private static class SessionBucket {
        final ConcurrentLinkedDeque<TimestampedMessage> fullMessages = new ConcurrentLinkedDeque<>();
        final ConcurrentLinkedDeque<TimestampedSummary> coolSummaries = new ConcurrentLinkedDeque<>();
        final AtomicInteger evictedCount = new AtomicInteger(0);
        volatile Instant lastDecayRun = Instant.now();
    }

    private record Experience(String category, String content, Instant timestamp) {}

    // ── State ───────────────────────────────────────────────

    private final ConcurrentHashMap<String, SessionBucket> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MemoryEntry> longTermMemories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Experience>> agentExperiences = new ConcurrentHashMap<>();
    private final AtomicInteger memoryIdCounter = new AtomicInteger(0);

    // BM25 parameters
    private static final double BM25_K1 = 1.4;
    private static final double BM25_B = 0.75;
    private static final Pattern TOKEN_RE = Pattern.compile("[A-Za-z0-9]+|[\\u4e00-\\u9fff]");
    private static final Set<String> STOP = Set.of(
        "the", "a", "an", "is", "are", "was", "were", "to", "of", "in", "on",
        "and", "or", "for", "with", "as", "by", "be", "this", "that", "it",
        "i", "you", "we", "they", "he", "she",
        "的", "了", "是", "在", "我", "你"
    );

    // ══════════════════════════════════════════════════════════════════
    //  Short-term: Session Memory
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void appendSessionMessage(String tenantId, String sessionId,
                                      String role, String content) {
        SessionBucket bucket = sessions.computeIfAbsent(
            sessionKey(tenantId, sessionId), k -> new SessionBucket());
        bucket.fullMessages.addLast(new TimestampedMessage(role, content, Instant.now()));
        MemorySkillMetrics.getInstance().recordSessionWrite(tenantId);
    }

    @Override
    public List<MemoryRecall> recallSession(String tenantId, String sessionId,
                                            String query, int limit,
                                            DecayPolicy policy) {
        SessionBucket bucket = sessions.get(sessionKey(tenantId, sessionId));
        if (bucket == null) return List.of();

        Instant now = Instant.now();
        List<MemoryRecall> results = new ArrayList<>();

        // FULL + WARM: from fullMessages
        for (TimestampedMessage tm : bucket.fullMessages) {
            long ageMs = Duration.between(tm.timestamp, now).toMillis();
            RecallStage stage = policy.classifyStage(ageMs);
            if (stage == RecallStage.EVICT) continue;
            // COOL should have been moved by decay cycle, but just in case:
            if (stage == RecallStage.COOL) continue;

            double weight = policy.stageWeight(stage);
            double relevance = query.isBlank() ? 1.0
                : textSimilarity(query, tm.content);
            double score = weight * relevance;

            results.add(new MemoryRecall(
                tm.content, tm.role, stage, score, false, tm.timestamp
            ));
        }

        // COOL: from coolSummaries
        for (TimestampedSummary ts : bucket.coolSummaries) {
            long ageMs = Duration.between(ts.generatedAt(), now).toMillis();
            RecallStage stage = policy.classifyStage(ageMs);
            if (stage == RecallStage.EVICT) continue;

            double weight = policy.stageWeight(RecallStage.COOL);
            double relevance = query.isBlank() ? 1.0
                : textSimilarity(query, ts.summary);
            double score = weight * relevance;

            results.add(new MemoryRecall(
                ts.summary, "system", RecallStage.COOL, score, true, ts.originalTime
            ));
        }

        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<MemoryRecall> result = results.subList(0, Math.min(limit, results.size()));
        MemorySkillMetrics.getInstance().recordSessionRead(tenantId);
        return result;
    }

    @Override
    public DecayResult runDecayCycle(String tenantId, String sessionId,
                                     DecayPolicy policy,
                                     SummaryFunction summariser,
                                     FactExtractor factExtractor) {
        SessionBucket bucket = sessions.get(sessionKey(tenantId, sessionId));
        if (bucket == null) {
            return new DecayResult(0, 0, 0, List.of(), Duration.ZERO);
        }

        Instant startTime = Instant.now();
        Instant now = Instant.now();
        long warmCutoffMs = policy.getWarmWindow().toMillis();
        long coolCutoffMs = policy.getCoolWindow().toMillis();

        // ── WARM -> COOL: batch summarise ───────────────────
        List<TimestampedMessage> toCompress = new ArrayList<>();
        Iterator<TimestampedMessage> it = bucket.fullMessages.iterator();
        while (it.hasNext()) {
            TimestampedMessage tm = it.next();
            long ageMs = Duration.between(tm.timestamp, now).toMillis();
            if (policy.classifyStage(ageMs) == RecallStage.COOL
                || policy.classifyStage(ageMs) == RecallStage.EVICT) {
                toCompress.add(tm);
                it.remove();
            }
        }

        int compressed = 0;
        if (toCompress.size() >= policy.getSummaryBatchSize()) {
            List<SessionMessage> msgs = toCompress.stream()
                .map(tm -> new SessionMessage(tm.role, tm.content, tm.timestamp))
                .toList();
            String summary = summariser.summarise(msgs);
            bucket.coolSummaries.addLast(new TimestampedSummary(
                summary, Instant.now(),
                toCompress.get(0).timestamp,
                toCompress.size()
            ));
            compressed = toCompress.size();
        } else {
            // Not enough to summarise yet; put them back
            bucket.fullMessages.addAll(toCompress);
        }

        // ── COOL -> EVICT: extract facts, write to long-term ──
        // Only evict summaries whose originalTime is older than coolWindow.
        // Summaries just created in this cycle (from WARM->COOL above) should
        // survive until the next cycle.
        int evicted = 0;
        List<String> facts = new ArrayList<>();
        Instant coolCutoff = now.minus(policy.getCoolWindow());

        Iterator<TimestampedSummary> sit = bucket.coolSummaries.iterator();
        while (sit.hasNext()) {
            TimestampedSummary ts = sit.next();
            // Use generatedAt to determine if the summary is old enough to evict
            if (ts.generatedAt().isBefore(coolCutoff)
                || Duration.between(ts.generatedAt(), now).toMillis() > coolCutoffMs) {

                if (policy.isExtractFactsOnEvict() && factExtractor != null) {
                    List<String> extracted = factExtractor.extract(
                        ts.summary, policy.getMaxFactsPerEviction());
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
                bucket.evictedCount.incrementAndGet();
                evicted++;
                sit.remove();
            }
        }

        bucket.lastDecayRun = Instant.now();
        Duration duration = Duration.between(startTime, Instant.now());
        MemorySkillMetrics.getInstance().recordDecay(
            tenantId, compressed, evicted, facts.size(), duration.toMillis());
        return new DecayResult(
            compressed, evicted, facts.size(), facts, duration
        );
    }

    @Override
    public void clearSession(String tenantId, String sessionId) {
        sessions.remove(sessionKey(tenantId, sessionId));
    }

    @Override
    public SessionMemoryStats getSessionStats(String tenantId, String sessionId) {
        SessionBucket bucket = sessions.get(sessionKey(tenantId, sessionId));
        if (bucket == null) {
            return new SessionMemoryStats(0, 0, 0, 0, null, null, null);
        }
        Instant now = Instant.now();
        int full = 0, warm = 0;
        Instant earliest = null, latest = null;
        for (TimestampedMessage tm : bucket.fullMessages) {
            long ageMs = Duration.between(tm.timestamp, now).toMillis();
            // Use standard policy for classification; caller can override if needed
            RecallStage stage = DecayPolicy.standard().classifyStage(ageMs);
            if (stage == RecallStage.FULL) full++;
            else if (stage == RecallStage.WARM) warm++;
            if (earliest == null || tm.timestamp.isBefore(earliest)) earliest = tm.timestamp;
            if (latest == null || tm.timestamp.isAfter(latest)) latest = tm.timestamp;
        }
        int cool = bucket.coolSummaries.size();
        int evicted = bucket.evictedCount.get();
        return new SessionMemoryStats(
            full, warm, cool, evicted, bucket.lastDecayRun, earliest, latest
        );
    }

    // ══════════════════════════════════════════════════════════════════
    //  Long-term: Agent / User Memory
    // ══════════════════════════════════════════════════════════════════

    @Override
    public String addMemory(MemoryEntry entry) {
        String id = entry.getId();
        if (id == null || id.isBlank()) {
            id = "mem_" + memoryIdCounter.incrementAndGet();
            entry.setId(id);
        }
        longTermMemories.put(id, entry);
        MemorySkillMetrics.getInstance().recordLongTermWrite(entry.getTenantId());
        return id;
    }

    @Override
    public List<MemoryEntry> searchMemories(String tenantId, String userId,
                                            String query, int limit) {
        return searchMemories(tenantId, userId, query, limit, RetrievalConfig.defaultRRF());
    }

    /**
     * Extended search with configurable retrieval strategy.
     */
    public List<MemoryEntry> searchMemories(String tenantId, String userId,
                                            String query, int limit,
                                            RetrievalConfig config) {
        // Filter valid memories for this tenant + user
        List<MemoryEntry> corpus = longTermMemories.values().stream()
            .filter(m -> tenantId.equals(m.getTenantId()))
            .filter(m -> userId == null || userId.equals(m.getUserId()))
            .filter(MemoryEntry::isValid)
            .toList();

        if (corpus.isEmpty()) return List.of();

        List<String> queryTokens = tokenize(query);

        // ── Channel 1: Semantic (cosine similarity) ────────
        float[] queryVec = null; // No embedding model in LocalMemoryStore; fall back to keyword
        List<ScoredEntry> semanticResults = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            MemoryEntry m = corpus.get(i);
            double sim;
            if (m.getEmbedding() != null && m.getEmbedding().length > 0 && queryVec != null) {
                sim = cosineSimilarity(queryVec, m.getEmbedding());
            } else {
                // Fall back to token overlap when no embeddings
                sim = tokenOverlap(queryTokens, m.getContent());
            }
            if (sim > 0) {
                semanticResults.add(new ScoredEntry(m, sim, i));
            }
        }
        semanticResults.sort((a, b) -> Double.compare(b.score, a.score));

        // ── Channel 2: BM25 ────────────────────────────────
        List<ScoredEntry> bm25Results = bm25Search(corpus, queryTokens);
        // bm25Search already returns sorted by score desc

        // ── Channel 3: Recency ──────────────────────────────
        List<ScoredEntry> recencyResults = new ArrayList<>(corpus.size());
        long now = System.currentTimeMillis();
        for (int i = 0; i < corpus.size(); i++) {
            MemoryEntry m = corpus.get(i);
            long age = now - m.getCreatedAt();
            double recencyScore = recencyScore(age, config.getDecay());
            recencyResults.add(new ScoredEntry(m, recencyScore, i));
        }
        recencyResults.sort((a, b) -> Double.compare(b.score, a.score));

        // ── Fusion ──────────────────────────────────────────
        int candidateLimit = Math.min(config.getCandidateLimit(), corpus.size());

        Map<MemoryEntry, Double> fusedScores = new HashMap<>();
        int rrfK = config.getRrfK();

        // RRF
        if (config.getStrategy() == FusionStrategy.RRF) {
            for (int rank = 0; rank < Math.min(candidateLimit, semanticResults.size()); rank++) {
                ScoredEntry se = semanticResults.get(rank);
                fusedScores.merge(se.entry, 1.0 / (rrfK + rank + 1), Double::sum);
            }
            for (int rank = 0; rank < Math.min(candidateLimit, bm25Results.size()); rank++) {
                ScoredEntry se = bm25Results.get(rank);
                fusedScores.merge(se.entry, 1.0 / (rrfK + rank + 1), Double::sum);
            }
            for (int rank = 0; rank < Math.min(candidateLimit, recencyResults.size()); rank++) {
                ScoredEntry se = recencyResults.get(rank);
                fusedScores.merge(se.entry, 1.0 / (rrfK + rank + 1), Double::sum);
            }
        } else {
            // WEIGHTED
            double sw = config.getSemanticWeight();
            double bw = config.getBm25Weight();
            double rw = config.getRecencyWeight();

            double maxSem = semanticResults.isEmpty() ? 0 : semanticResults.get(0).score;
            double maxBm = bm25Results.isEmpty() ? 0 : bm25Results.get(0).score;

            for (ScoredEntry se : semanticResults) {
                double norm = maxSem > 0 ? se.score / maxSem : 0;
                fusedScores.merge(se.entry, norm * sw, Double::sum);
            }
            for (ScoredEntry se : bm25Results) {
                double norm = maxBm > 0 ? se.score / maxBm : 0;
                fusedScores.merge(se.entry, norm * bw, Double::sum);
            }
            for (ScoredEntry se : recencyResults) {
                fusedScores.merge(se.entry, se.score * rw, Double::sum);
            }
        }

        return fusedScores.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    private List<MemoryEntry> searchMemoriesWithMetrics(String tenantId, String userId,
                                            String query, int limit,
                                            RetrievalConfig config) {
        long start = System.currentTimeMillis();
        List<MemoryEntry> results = searchMemories(tenantId, userId, query, limit, config);
        MemorySkillMetrics.getInstance().recordSearch(
            tenantId, System.currentTimeMillis() - start, results.size());
        return results;
    }

    @Override
    public void updateMemory(String memoryId, MemoryEntry entry) {
        entry.setId(memoryId);
        longTermMemories.put(memoryId, entry);
    }

    @Override
    public void invalidateMemory(String memoryId) {
        MemoryEntry m = longTermMemories.get(memoryId);
        if (m != null) {
            m.setValidUntil(System.currentTimeMillis());
        }
    }

    @Override
    public void deleteMemory(String memoryId) {
        longTermMemories.remove(memoryId);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Agent Experience
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void addAgentExperience(String tenantId, String agentId,
                                    String category, String content) {
        String key = agentKey(tenantId, agentId);
        agentExperiences.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>())
            .add(new Experience(category, content, Instant.now()));
    }

    @Override
    public List<String> getAgentExperiences(String tenantId, String agentId,
                                            String category, int limit) {
        String key = agentKey(tenantId, agentId);
        ConcurrentLinkedQueue<Experience> queue = agentExperiences.get(key);
        if (queue == null) return List.of();

        return queue.stream()
            .filter(e -> category == null || category.equals(e.category))
            .sorted((a, b) -> b.timestamp.compareTo(a.timestamp))
            .limit(limit)
            .map(e -> e.content)
            .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════════════════════════
    //  BM25 + helper methods
    // ══════════════════════════════════════════════════════════════════

    private List<ScoredEntry> bm25Search(List<MemoryEntry> corpus, List<String> queryTokens) {
        if (queryTokens.isEmpty()) return List.of();

        // Tokenise corpus
        List<List<String>> docTokens = new ArrayList<>(corpus.size());
        int totalLen = 0;
        for (MemoryEntry m : corpus) {
            List<String> tokens = tokenize(m.getContent());
            docTokens.add(tokens);
            totalLen += tokens.size();
        }
        double avgLen = corpus.isEmpty() ? 0 : (double) totalLen / corpus.size();
        if (avgLen == 0) return List.of();

        // Document frequency
        Map<String, Integer> df = new HashMap<>();
        for (List<String> tokens : docTokens) {
            Set<String> uniq = new HashSet<>(tokens);
            for (String t : uniq) df.merge(t, 1, Integer::sum);
        }

        int N = corpus.size();
        List<ScoredEntry> scored = new ArrayList<>(N);
        for (int i = 0; i < corpus.size(); i++) {
            List<String> tokens = docTokens.get(i);
            double score = bm25Score(queryTokens, tokens, df, N, avgLen);
            if (score > 0) {
                scored.add(new ScoredEntry(corpus.get(i), score, i));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored;
    }

    private static double bm25Score(List<String> query, List<String> doc,
                                    Map<String, Integer> df, int N, double avgLen) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : doc) tf.merge(t, 1, Integer::sum);

        double score = 0.0;
        int docLen = doc.size();
        for (String q : query) {
            int freq = tf.getOrDefault(q, 0);
            if (freq == 0) continue;
            int d = df.getOrDefault(q, 0);
            double idf = Math.log(1 + (double) (N - d + 0.5) / (d + 0.5));
            double norm = freq * (BM25_K1 + 1) /
                (freq + BM25_K1 * (1 - BM25_B + BM25_B * (docLen / avgLen)));
            score += idf * norm;
        }
        return score;
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN_RE.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String tok = m.group();
            if (STOP.contains(tok)) continue;
            if (tok.length() == 1 && !isCJK(tok.charAt(0))) continue;
            out.add(tok);
        }
        return out;
    }

    private static boolean isCJK(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    private double textSimilarity(String query, String content) {
        // Simple token overlap for local mode (no embedding model)
        List<String> qTokens = tokenize(query);
        List<String> cTokens = tokenize(content);
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0;

        Set<String> qSet = new HashSet<>(qTokens);
        Set<String> cSet = new HashSet<>(cTokens);

        // Jaccard-like overlap
        Set<String> intersection = new HashSet<>(qSet);
        intersection.retainAll(cSet);

        Set<String> union = new HashSet<>(qSet);
        union.addAll(cSet);

        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private double tokenOverlap(List<String> queryTokens, String content) {
        if (queryTokens.isEmpty()) return 0;
        Set<String> contentTokens = new HashSet<>(tokenize(content));
        if (contentTokens.isEmpty()) return 0;

        long hits = queryTokens.stream().filter(contentTokens::contains).count();
        return (double) hits / queryTokens.size();
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double recencyScore(long ageMs, RetrievalConfig.RecencyDecay decay) {
        long dayMs = 86_400_000L;
        double ageDays = (double) ageMs / dayMs;
        return switch (decay) {
            case LINEAR -> Math.max(0, 1.0 - ageDays / 365.0);
            case EXPONENTIAL -> Math.exp(-ageDays / 30.0);
            case LOG -> 1.0 / (1.0 + Math.log10(1 + ageDays));
        };
    }

    // ── Helper ──────────────────────────────────────────────

    private record ScoredEntry(MemoryEntry entry, double score, int originalIndex) {}
}
