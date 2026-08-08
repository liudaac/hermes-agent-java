package com.nousresearch.hermes.session;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.gateway.SessionManager;
import com.nousresearch.hermes.improvement.SignalCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Session reference injector - builds structured reference context
 * from a historical session and injects it into a new session's message.
 *
 * <p>This enables the "select a past session as execution flow reference"
 * use case: the user picks a completed session, and its structured steps
 * are injected as context into the new session's first message.</p>
 */
public class SessionReference {

    private static final Logger logger = LoggerFactory.getLogger(SessionReference.class);

    private final SessionLibrary library;
    private final SessionStepExtractor extractor;
    private SignalCollector signalCollector;

    public SessionReference(SessionLibrary library) {
        this.library = library;
        this.extractor = new SessionStepExtractor();
    }

    /**
     * Set the signal collector for emitting SESSION_REFERENCE signals.
     * Optional: if not set, no signals are emitted.
     */
    public void setSignalCollector(SignalCollector collector) {
        this.signalCollector = collector;
    }

    /**
     * Build the reference context string for a given session.
     *
     * @param tenantId  tenant
     * @param sessionId the historical session to reference
     * @return formatted reference context, or null if session not found
     */
    public String buildReference(String tenantId, String sessionId) {
        SessionAsset asset = library.getAsset(tenantId, sessionId);
        if (asset == null) {
            logger.warn("Session asset not found for reference: {}/{}", tenantId, sessionId);
            return null;
        }

        List<SessionAsset.StepSummary> steps = library.getSteps(tenantId, sessionId);
        if (steps == null || steps.isEmpty()) {
            // Fallback: try to extract from the raw session
            try {
                var sessionMgr = new SessionManager(Constants.getHermesHome());
                var session = sessionMgr.getSession(sessionId);
                steps = extractor.extract(session);
            } catch (Exception e) {
                logger.warn("Failed to extract steps from raw session: {}", e.getMessage());
            }
        }

        String title = asset.title() != null ? asset.title() : "历史会话";
        return extractor.buildReferenceContext(title, steps);
    }

    /**
     * Inject reference context into a user message.
     *
     * <p>If referenceContext is null or blank, returns the original message unchanged.
     * Otherwise, prepends the reference context before the user's message.</p>
     *
     * @param userMessage     the user's new message
     * @param referenceContext the reference context to inject
     * @return the augmented message
     */
    public String injectReference(String userMessage, String referenceContext) {
        if (referenceContext == null || referenceContext.isBlank()) {
            return userMessage;
        }
        return referenceContext + "\n\n---\n\n" + userMessage;
    }

    /**
     * One-shot convenience: build reference from a session and inject
     * into a user message.
     *
     * @param tenantId       tenant
     * @param referenceSessionId the session to use as reference
     * @param userMessage    the user's new message
     * @return the augmented message with reference context prepended
     */
    public String injectReferenceFromSession(String tenantId, String referenceSessionId,
                                              String userMessage) {
        String referenceContext = buildReference(tenantId, referenceSessionId);
        String result = injectReference(userMessage, referenceContext);
        if (signalCollector != null && referenceContext != null && !referenceContext.isBlank()) {
            signalCollector.onSessionReference(tenantId, null, referenceSessionId);
        }
        return result;
    }
}
