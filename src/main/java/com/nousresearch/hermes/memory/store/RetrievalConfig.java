package com.nousresearch.hermes.memory.store;

/**
 * Retrieval configuration for long-term memory search.
 *
 * <p>Controls how the three retrieval channels (semantic, BM25, recency)
 * are combined into the final ranked list.</p>
 */
public class RetrievalConfig {

    private FusionStrategy strategy = FusionStrategy.RRF;
    private double semanticWeight = 0.4;
    private double bm25Weight = 0.4;
    private double recencyWeight = 0.2;
    private int candidateLimit = 100;
    private boolean chineseTokenizer = true;
    private RecencyDecay decay = RecencyDecay.LOG;
    /** RRF k parameter (default 60). */
    private int rrfK = 60;

    public enum RecencyDecay { LINEAR, EXPONENTIAL, LOG }

    public static RetrievalConfig defaultRRF() {
        return new RetrievalConfig();
    }

    public static RetrievalConfig weighted(double semantic, double bm25, double recency) {
        RetrievalConfig c = new RetrievalConfig();
        c.strategy = FusionStrategy.WEIGHTED;
        c.semanticWeight = semantic;
        c.bm25Weight = bm25;
        c.recencyWeight = recency;
        return c;
    }

    // ── Getters / Setters ────────────────────────────────────

    public FusionStrategy getStrategy() { return strategy; }
    public void setStrategy(FusionStrategy strategy) { this.strategy = strategy; }

    public double getSemanticWeight() { return semanticWeight; }
    public void setSemanticWeight(double v) { this.semanticWeight = v; }

    public double getBm25Weight() { return bm25Weight; }
    public void setBm25Weight(double v) { this.bm25Weight = v; }

    public double getRecencyWeight() { return recencyWeight; }
    public void setRecencyWeight(double v) { this.recencyWeight = v; }

    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int v) { this.candidateLimit = v; }

    public boolean isChineseTokenizer() { return chineseTokenizer; }
    public void setChineseTokenizer(boolean v) { this.chineseTokenizer = v; }

    public RecencyDecay getDecay() { return decay; }
    public void setDecay(RecencyDecay decay) { this.decay = decay; }

    public int getRrfK() { return rrfK; }
    public void setRrfK(int rrfK) { this.rrfK = rrfK; }
}
