package com.nousresearch.hermes.improvement;

import com.nousresearch.hermes.memory.store.LocalMemoryStore;
import com.nousresearch.hermes.memory.store.MemoryEntry;
import com.nousresearch.hermes.memory.store.MemoryStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PreferenceLearningEngine} and {@link PatternEvolutionProposer}.
 */
class PreferenceLearningTest {

    @TempDir
    Path tempDir;

    private LocalSignalStore signalStore;
    private LocalProposalStore proposalStore;
    private LocalMemoryStore memoryStore;
    private SignalCollector collector;
    private ImprovementConfirmationFlow confirmationFlow;
    private PreferenceLearningEngine learningEngine;
    private PatternEvolutionProposer proposer;

    private final String tenantId = "test-tenant";
    private final String userId = "usr_test";

    @BeforeEach
    void setUp() {
        System.setProperty("hermes.home", tempDir.toString());
        signalStore = new LocalSignalStore();
        proposalStore = new LocalProposalStore();
        memoryStore = new LocalMemoryStore();
        MemoryStoreFactory.set(memoryStore);

        collector = new SignalCollector(signalStore);
        confirmationFlow = new ImprovementConfirmationFlow(proposalStore);
        learningEngine = new PreferenceLearningEngine(signalStore, memoryStore, confirmationFlow);
        proposer = new PatternEvolutionProposer(signalStore, confirmationFlow);
    }

    @AfterEach
    void tearDown() {
        MemoryStoreFactory.reset();
    }

    // ── PreferenceLearningEngine: bookmarks ──

    @Test
    void learnsFromBookmarksAboveThreshold() {
        // 3 bookmarks about deployment
        for (int i = 0; i < 3; i++) {
            collector.onBookmark(tenantId, userId, "ses_" + i, "deploy session");
        }

        List<PreferenceUpdate> updates = learningEngine.learnFromSignals(tenantId, userId);

        // Should learn a session_interest preference
        PreferenceUpdate bookmarkPref = updates.stream()
                .filter(u -> "session_interest".equals(u.key()))
                .findFirst()
                .orElse(null);
        assertNotNull(bookmarkPref);
        assertEquals("deployment", bookmarkPref.newValue());
        assertTrue(bookmarkPref.confidence() > 0);
    }

    @Test
    void doesNotLearnFromTooFewBookmarks() {
        collector.onBookmark(tenantId, userId, "ses_1", "deploy");
        collector.onBookmark(tenantId, userId, "ses_2", "deploy");

        List<PreferenceUpdate> updates = learningEngine.learnFromSignals(tenantId, userId);
        assertFalse(updates.stream().anyMatch(u -> "session_interest".equals(u.key())));
    }

    // ── PreferenceLearningEngine: ratings ──

    @Test
    void learnsFromHighRatings() {
        for (int i = 0; i < 3; i++) {
            collector.onRatingHigh(tenantId, userId, "ses_" + i, 5);
        }

        List<PreferenceUpdate> updates = learningEngine.learnFromSignals(tenantId, userId);
        assertTrue(updates.stream().anyMatch(u -> "satisfaction".equals(u.key()) && "high".equals(u.newValue())));
    }

    @Test
    void learnsFromLowRatings() {
        collector.onRatingLow(tenantId, userId, "ses_1", 1);
        collector.onRatingLow(tenantId, userId, "ses_2", 2);

        List<PreferenceUpdate> updates = learningEngine.learnFromSignals(tenantId, userId);
        assertTrue(updates.stream().anyMatch(u -> "satisfaction".equals(u.key()) && "low".equals(u.newValue())));
    }

    // ── PreferenceLearningEngine: explicit feedback ──

    @Test
    void learnsFromExplicitFeedback() {
        signalStore.save(ImprovementSignal.create(
                tenantId, userId, SignalType.EXPLICIT_FEEDBACK, "ses_1",
                "User feedback: response_style: concise", 1.0));

        List<PreferenceUpdate> updates = learningEngine.learnFromSignals(tenantId, userId);
        PreferenceUpdate feedback = updates.stream()
                .filter(u -> "response_style".equals(u.key()))
                .findFirst()
                .orElse(null);
        assertNotNull(feedback);
        assertEquals("concise", feedback.newValue());
        assertEquals(1.0, feedback.confidence());
    }

}
