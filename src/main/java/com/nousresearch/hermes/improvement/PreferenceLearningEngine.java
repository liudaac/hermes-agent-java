package com.nousresearch.hermes.improvement;

import com.nousresearch.hermes.memory.store.MemoryEntry;
import com.nousresearch.hermes.memory.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Learns user preferences from accumulated improvement signals and writes
 * them to {@link MemoryStore} as MemoryEntry(type=PREFERENCE).
 *
 * <p>Preference application path:</p>
 * <pre>
 *   Signals accumulate in SignalStore
 *   -> PreferenceLearningEngine.extractPreference() extracts PreferenceUpdate
 *   -> applyPreference() writes MemoryEntry(type=PREFERENCE) to MemoryStore
 *   -> Next session's searchMemories() naturally retrieves the preference
 *   -> Injected into context (no separate injection mechanism needed)
 * </pre>
 *
 * <p>Confidence model:</p>
 * <ul>
 *   <li>confidence < 0.6: not auto-applied, PROMPT proposal generated</li>
 *   <li>confidence >= 0.6: auto-applied (AUTO mode)</li>
 *   <li>High-risk preferences (execution_order): REQUIRE confirmation</li>
 * </ul>
 */
public class PreferenceLearningEngine {

    private static final Logger logger = LoggerFactory.getLogger(PreferenceLearningEngine.class);

    private final SignalStore signalStore;
    private final MemoryStore memoryStore;
    private final ImprovementConfirmationFlow confirmationFlow;

    /** Minimum signals of same type before learning a preference */
    private static final int MIN_SIGNALS_FOR_LEARNING = 3;

    /** Minimum confidence to auto-apply (below this -> PROMPT) */
    private static final double AUTO_APPLY_THRESHOLD = 0.6;

    /** Preference keys that require explicit confirmation (high risk) */
    private static final java.util.Set<String> REQUIRE_CONFIRM_KEYS = java.util.Set.of(
            "execution_order"
    );

    public PreferenceLearningEngine(SignalStore signalStore,
                                     MemoryStore memoryStore,
                                     ImprovementConfirmationFlow confirmationFlow) {
        this.signalStore = signalStore;
        this.memoryStore = memoryStore;
        this.confirmationFlow = confirmationFlow;
    }

    /**
     * Attempt to learn preferences from a user's accumulated signals.
     *
     * @return list of applied/pending PreferenceUpdates (empty if nothing learned)
     */
    public List<PreferenceUpdate> learnFromSignals(String tenantId, String userId) {
        List<PreferenceUpdate> updates = new ArrayList<>();

        // Learn from BOOKMARK signals -> "user values this type of session"
        updates.addAll(learnFromBookmarks(tenantId, userId));

        // Learn from RATING_HIGH signals -> "user likes this approach"
        updates.addAll(learnFromHighRatings(tenantId, userId));

        // Learn from RATING_LOW signals -> "user dislikes this approach"
        updates.addAll(learnFromLowRatings(tenantId, userId));

        // Learn from EXPLICIT_FEEDBACK signals -> direct preference extraction
        updates.addAll(learnFromExplicitFeedback(tenantId, userId));

        // Learn from USER_CORRECTION signals -> "agent made mistakes in this area"
        updates.addAll(learnFromCorrections(tenantId, userId));

        return updates;
    }

    /**
     * Apply a preference update to MemoryStore.
     * Routes to AUTO or PROMPT/REQUIRE based on confidence and key.
     *
     * @return the ImprovementProposal (applied, pending, or require-confirm)
     */
    public ImprovementProposal applyPreference(String tenantId, String userId,
                                                PreferenceUpdate pref) {
        String evidence = pref.evidence() != null ? pref.evidence() :
                "Learned from signals (confidence=" + String.format("%.2f", pref.confidence()) + ")";

        // Build proposal
        ImprovementProposal template = ImprovementProposal.pending(
                tenantId, userId,
                "Preference: " + pref.key(),
                "Learned preference: " + pref.key() + " = " + pref.newValue(),
                "Set " + pref.key() + " to " + pref.newValue(),
                "More personalized agent responses",
                evidence,
                pref.confidence()
        );

        // Route based on risk
        if (pref.confidence() >= AUTO_APPLY_THRESHOLD && !REQUIRE_CONFIRM_KEYS.contains(pref.key())) {
            // AUTO: apply directly to MemoryStore
            writePreferenceToMemory(tenantId, userId, pref);
            return confirmationFlow.autoApply(template);
        } else if (REQUIRE_CONFIRM_KEYS.contains(pref.key())) {
            // REQUIRE: high-risk preference, need explicit confirmation
            return confirmationFlow.requireConfirm(template);
        } else {
            // PROMPT: low confidence, user can review
            return confirmationFlow.proposePending(template);
        }
    }

    // ── Signal -> Preference extraction ──────────────────────

    private List<PreferenceUpdate> learnFromBookmarks(String tenantId, String userId) {
        int bookmarkCount = signalStore.countByType(tenantId, userId, SignalType.BOOKMARK);
        if (bookmarkCount < MIN_SIGNALS_FOR_LEARNING) {
            return List.of();
        }

        List<ImprovementSignal> bookmarks = signalStore.queryByType(
                tenantId, userId, SignalType.BOOKMARK);

        // Extract common themes from bookmark content
        Map<String, Integer> themeCount = new HashMap<>();
        for (ImprovementSignal sig : bookmarks) {
            String content = sig.content().toLowerCase();
            // Simple keyword-based theme extraction
            if (content.contains("deploy") || content.contains("部署")) {
                themeCount.merge("deployment", 1, Integer::sum);
            }
            if (content.contains("debug") || content.contains("调试")) {
                themeCount.merge("debugging", 1, Integer::sum);
            }
            if (content.contains("test") || content.contains("测试")) {
                themeCount.merge("testing", 1, Integer::sum);
            }
        }

        List<PreferenceUpdate> updates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : themeCount.entrySet()) {
            if (entry.getValue() >= MIN_SIGNALS_FOR_LEARNING) {
                double confidence = Math.min(1.0, entry.getValue() / (double) bookmarkCount);
                updates.add(new PreferenceUpdate(
                        userId, "session_interest", null, entry.getKey(),
                        confidence,
                        "Based on " + entry.getValue() + " bookmarks out of " + bookmarkCount
                ));
            }
        }
        return updates;
    }

    private List<PreferenceUpdate> learnFromHighRatings(String tenantId, String userId) {
        int highCount = signalStore.countByType(tenantId, userId, SignalType.RATING_HIGH);
        if (highCount < MIN_SIGNALS_FOR_LEARNING) {
            return List.of();
        }

        // High ratings indicate the user is satisfied with the current approach
        return List.of(new PreferenceUpdate(
                userId, "satisfaction", null, "high",
                Math.min(1.0, highCount / 5.0),
                "Based on " + highCount + " high ratings (>= 4 stars)"
        ));
    }

    private List<PreferenceUpdate> learnFromLowRatings(String tenantId, String userId) {
        int lowCount = signalStore.countByType(tenantId, userId, SignalType.RATING_LOW);
        if (lowCount < 2) {  // Lower threshold for negative signals
            return List.of();
        }

        return List.of(new PreferenceUpdate(
                userId, "satisfaction", null, "low",
                Math.min(1.0, lowCount / 3.0),
                "Based on " + lowCount + " low ratings (<= 2 stars) - agent needs improvement"
        ));
    }

    private List<PreferenceUpdate> learnFromExplicitFeedback(String tenantId, String userId) {
        List<ImprovementSignal> feedbackSignals = signalStore.queryByType(
                tenantId, userId, SignalType.EXPLICIT_FEEDBACK);

        List<PreferenceUpdate> updates = new ArrayList<>();
        for (ImprovementSignal sig : feedbackSignals) {
            if (sig.processed()) continue;

            // Parse "key: value" from feedback content
            String content = sig.content();
            if (content.startsWith("User feedback: ")) {
                content = content.substring("User feedback: ".length());
            }
            String[] parts = content.split(":", 2);
            if (parts.length == 2) {
                String key = parts[0].trim();
                String value = parts[1].trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    updates.add(new PreferenceUpdate(
                            userId, key, null, value, 1.0,
                            "Explicit user feedback in session " + sig.sessionId()
                    ));
                }
            }
        }
        return updates;
    }

    private List<PreferenceUpdate> learnFromCorrections(String tenantId, String userId) {
        int correctionCount = signalStore.countByType(tenantId, userId, SignalType.USER_CORRECTION);
        if (correctionCount < MIN_SIGNALS_FOR_LEARNING) {
            return List.of();
        }

        return List.of(new PreferenceUpdate(
                userId, "accuracy_issue", null, "frequent_corrections",
                Math.min(1.0, correctionCount / 5.0),
                "Based on " + correctionCount + " user corrections - agent may need accuracy improvement"
        ));
    }

    // ── MemoryStore write ────────────────────────────────────

    private void writePreferenceToMemory(String tenantId, String userId,
                                          PreferenceUpdate pref) {
        try {
            MemoryEntry entry = MemoryEntry.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .type(MemoryEntry.MemoryType.PREFERENCE)
                    .content(pref.key() + ": " + pref.newValue())
                    .category(pref.key())
                    .source("preference_learning:" + pref.evidence())
                    .build();

            memoryStore.addMemory(entry);
            logger.info("Preference written to MemoryStore: {} = {} (confidence={})",
                        pref.key(), pref.newValue(), String.format("%.2f", pref.confidence()));
        } catch (Exception e) {
            logger.error("Failed to write preference to MemoryStore: {}", e.getMessage());
        }
    }
}
