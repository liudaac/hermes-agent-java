package com.nousresearch.hermes.improvement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects deterministic improvement signals from user actions.
 *
 * <p>This collector is called from SessionLibrary (bookmark/rate) and
 * SessionReference (reference injection) to emit signals.</p>
 *
 * <p>LLM-assisted signals (USER_CORRECTION, REPEAT_PATTERN,
 * EXPLICIT_FEEDBACK) are handled in Sprint 4b.</p>
 */
public class SignalCollector {

    private static final Logger logger = LoggerFactory.getLogger(SignalCollector.class);

    private final SignalStore signalStore;

    // Default weights per signal type
    private static final double WEIGHT_BOOKMARK = 0.6;
    private static final double WEIGHT_RATING_HIGH = 0.8;
    private static final double WEIGHT_RATING_LOW = 1.0;
    private static final double WEIGHT_SESSION_REFERENCE = 0.7;

    public SignalCollector(SignalStore signalStore) {
        this.signalStore = signalStore;
    }

    /**
     * Emit a BOOKMARK signal when a user bookmarks a session.
     */
    public void onBookmark(String tenantId, String userId, String sessionId, String note) {
        String content = "User bookmarked session " + sessionId +
                         (note != null && !note.isBlank() ? " with note: " + note : "");
        emit(tenantId, userId, SignalType.BOOKMARK, sessionId, content, WEIGHT_BOOKMARK);
    }

    /**
     * Emit a RATING_HIGH signal when a user rates a session >= 4.
     */
    public void onRatingHigh(String tenantId, String userId, String sessionId, int rating) {
        String content = "User rated session " + sessionId + " with " + rating + " stars (high)";
        emit(tenantId, userId, SignalType.RATING_HIGH, sessionId, content, WEIGHT_RATING_HIGH);
    }

    /**
     * Emit a RATING_LOW signal when a user rates a session <= 2.
     */
    public void onRatingLow(String tenantId, String userId, String sessionId, int rating) {
        String content = "User rated session " + sessionId + " with " + rating + " stars (low)";
        emit(tenantId, userId, SignalType.RATING_LOW, sessionId, content, WEIGHT_RATING_LOW);
    }

    /**
     * Emit a SESSION_REFERENCE signal when a user references a past session.
     */
    public void onSessionReference(String tenantId, String userId, String referenceSessionId) {
        String content = "User referenced historical session " + referenceSessionId + " in a new session";
        emit(tenantId, userId, SignalType.SESSION_REFERENCE, referenceSessionId, content, WEIGHT_SESSION_REFERENCE);
    }

    /**
     * Emit a signal to the store.
     */
    private void emit(String tenantId, String userId, SignalType type,
                      String sessionId, String content, double weight) {
        ImprovementSignal signal = ImprovementSignal.create(
                tenantId, userId, type, sessionId, content, weight);
        signalStore.save(signal);
        logger.debug("Emitted signal: type={}, user={}, weight={}", type, userId, weight);
    }
}
