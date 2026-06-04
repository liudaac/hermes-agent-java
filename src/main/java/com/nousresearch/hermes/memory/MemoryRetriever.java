package com.nousresearch.hermes.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lexical (BM25-lite) retriever over {@link MemoryManager} entries.
 *
 * <p>Used to pick the top-K most relevant memory entries for the current
 * user turn, so the system prompt only injects what matters instead of the
 * whole MEMORY.md / USER.md. This preserves prefix cache for the long tail
 * while still keeping the agent context-aware.
 *
 * <p>The scoring is intentionally lightweight (no external indexer): a
 * tokenised BM25-like score with IDF computed on the fly across the small
 * memory corpus (typically &lt; 100 entries). Good enough until we wire in
 * a real embedding store.
 */
public class MemoryRetriever {

    private static final Logger logger = LoggerFactory.getLogger(MemoryRetriever.class);

    // BM25 parameters
    private static final double K1 = 1.4;
    private static final double B  = 0.75;

    // Token splitting: words + Chinese characters (treat each CJK char as a token)
    private static final Pattern TOKEN_RE = Pattern.compile("[A-Za-z0-9]+|[\\u4e00-\\u9fff]");

    // Tiny stopword set; aggressive on purpose (memory entries are short)
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "to", "of", "in", "on",
            "and", "or", "for", "with", "as", "by", "be", "this", "that", "it",
            "i", "you", "we", "they", "he", "she",
            "的", "了", "是", "在", "我", "你"
    );

    private final MemoryManager memoryManager;

    public MemoryRetriever(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * Score and return the top-K memory entries for {@code query}, drawn from
     * both system memory and user-profile memory.
     *
     * @param query  the current user message (or other relevance signal)
     * @param topK   max entries to return (across both categories)
     * @return ranked list of {@link RetrievedEntry}; empty if nothing matches
     */
    public List<RetrievedEntry> retrieve(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return List.of();

        // Pull all entries (small corpora; cheap)
        List<String> memEntries = memoryManager.getByCategory("memory", 200);
        List<String> userEntries = memoryManager.getByCategory("user", 200);

        List<EntryRef> corpus = new ArrayList<>();
        for (String e : memEntries) corpus.add(new EntryRef(e, "memory"));
        for (String e : userEntries) corpus.add(new EntryRef(e, "user"));
        if (corpus.isEmpty()) return List.of();

        // Tokenise corpus
        List<List<String>> docTokens = new ArrayList<>(corpus.size());
        int totalLen = 0;
        for (EntryRef ref : corpus) {
            List<String> tokens = tokenize(ref.content);
            docTokens.add(tokens);
            totalLen += tokens.size();
        }
        double avgLen = totalLen / (double) corpus.size();

        // Document frequency for IDF
        Map<String, Integer> df = new HashMap<>();
        for (List<String> tokens : docTokens) {
            Set<String> uniq = new HashSet<>(tokens);
            for (String t : uniq) df.merge(t, 1, Integer::sum);
        }

        // Score each document
        int N = corpus.size();
        List<RetrievedEntry> scored = new ArrayList<>(N);
        for (int i = 0; i < corpus.size(); i++) {
            EntryRef ref = corpus.get(i);
            List<String> tokens = docTokens.get(i);
            double score = bm25(queryTokens, tokens, df, N, avgLen);
            if (score > 0) {
                scored.add(new RetrievedEntry(ref.content, ref.category, score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<RetrievedEntry> top = scored.stream().limit(topK).collect(Collectors.toList());

        if (logger.isDebugEnabled()) {
            logger.debug("Retrieved {} memory entries for query: '{}'", top.size(),
                    query.substring(0, Math.min(60, query.length())));
        }
        return top;
    }

    /**
     * Total number of memory entries available (across categories).
     * Useful for the "+N more" hint in the system prompt.
     */
    public int totalEntries() {
        return memoryManager.getByCategory("memory", Integer.MAX_VALUE).size()
                + memoryManager.getByCategory("user",   Integer.MAX_VALUE).size();
    }

    // ------------------------------------------------------------------
    // Scoring internals
    // ------------------------------------------------------------------

    private static double bm25(List<String> query, List<String> doc,
                               Map<String, Integer> df, int N, double avgLen) {
        // Term frequency in doc
        Map<String, Integer> tf = new HashMap<>();
        for (String t : doc) tf.merge(t, 1, Integer::sum);

        double score = 0.0;
        int docLen = doc.size();
        for (String q : query) {
            int freq = tf.getOrDefault(q, 0);
            if (freq == 0) continue;
            int d = df.getOrDefault(q, 0);
            // Robertson-Sparck-Jones IDF (smoothed)
            double idf = Math.log(1 + (N - d + 0.5) / (d + 0.5));
            double norm = freq * (K1 + 1) /
                          (freq + K1 * (1 - B + B * (docLen / avgLen)));
            score += idf * norm;
        }
        return score;
    }

    static List<String> tokenize(String text) {
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN_RE.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String tok = m.group();
            if (STOP.contains(tok)) continue;
            if (tok.length() == 1 && !isCJK(tok.charAt(0))) continue; // drop single ASCII letters
            out.add(tok);
        }
        return out;
    }

    private static boolean isCJK(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    // ------------------------------------------------------------------

    private record EntryRef(String content, String category) {}

    public static final class RetrievedEntry {
        public final String content;
        public final String category;   // "memory" | "user"
        public final double score;

        public RetrievedEntry(String content, String category, double score) {
            this.content = content;
            this.category = category;
            this.score = score;
        }
    }
}
