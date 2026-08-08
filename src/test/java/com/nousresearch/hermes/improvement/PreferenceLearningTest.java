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

    @Test
    void learnsFromCorrections() {
        for (int i = 0; i < 3; i++) {
            signalStore.save(ImprovementSignal.create(
                    tenantId, userId, SignalType.USER_CORRECTION, "ses_" + i,
                    "User correction detected: wrong API name", 1.0));
        }

        List<PreferenceUpdate> updates = learningEngine.learnFromSignals(tenantId, userId);
        assertTrue(updates.stream().anyMatch(u -> "accuracy_issue".equals(u.key())));
    }

    // ── PreferenceLearningEngine: apply ──

    @Test
    void applyHighConfidenceAutoApplies() {
        PreferenceUpdate pref = new PreferenceUpdate(
                userId, "response_style", null, "concise", 0.8, "test evidence");

        ImprovementProposal result = learningEngine.applyPreference(tenantId, userId, pref);

        assertEquals(ProposalStatus.AUTO_APPLIED, result.status());

        // Verify preference was written to MemoryStore
        List<MemoryEntry> memories = memoryStore.searchMemories(tenantId, userId, "response_style", 10);
        assertFalse(memories.isEmpty());
        assertTrue(memories.stream().anyMatch(m -> m.getContent().contains("concise")));
    }

    @Test
    void applyLowConfidenceGoesPending() {
        PreferenceUpdate pref = new PreferenceUpdate(
                userId, "response_style", null, "concise", 0.4, "low confidence");

        ImprovementProposal result = learningEngine.applyPreference(tenantId, userId, pref);

        assertEquals(ProposalStatus.PENDING, result.status());

        // Should NOT be in MemoryStore (pending)
        List<MemoryEntry> memories = memoryStore.searchMemories(tenantId, userId, "response_style", 10);
        assertTrue(memories.isEmpty());
    }

    @Test
    void applyExecutionOrderRequiresConfirm() {
        PreferenceUpdate pref = new PreferenceUpdate(
                userId, "execution_order", null, "test_before_deploy", 0.9, "high confidence but high risk");

        ImprovementProposal result = learningEngine.applyPreference(tenantId, userId, pref);

        assertEquals(ProposalStatus.REQUIRE_CONFIRM, result.status());
    }

    @Test
    void applyExecutionOrderNotAutoWrittenToMemory() {
        PreferenceUpdate pref = new PreferenceUpdate(
                userId, "execution_order", null, "test_before_deploy", 0.9, "test");

        learningEngine.applyPreference(tenantId, userId, pref);

        // Require-confirm: NOT written to memory yet
        List<MemoryEntry> memories = memoryStore.searchMemories(tenantId, userId, "execution_order", 10);
        assertTrue(memories.isEmpty());
    }

    // ── PreferenceLearningEngine: end-to-end ──

    @Test
    void endToEndLearnAndApply() {
        // User gives explicit feedback
        signalStore.save(ImprovementSignal.create(
                tenantId, userId, SignalType.EXPLICIT_FEEDBACK, "ses_1",
                "User feedback: response_style: detailed", 1.0));

        // Learn
        List<PreferenceUpdate> updates = learningEngine.learnFromSignals(tenantId, userId);
        assertFalse(updates.isEmpty());

        // Apply (confidence=1.0 -> AUTO)
        for (PreferenceUpdate pref : updates) {
            ImprovementProposal result = learningEngine.applyPreference(tenantId, userId, pref);
            assertNotNull(result);
        }

        // Verify memory has the preference
        List<MemoryEntry> memories = memoryStore.searchMemories(tenantId, userId, "response_style", 10);
        assertFalse(memories.isEmpty());
    }

    // ── PatternEvolutionProposer ──

    @Test
    void proposerDetectsRepeatPattern() {
        for (int i = 0; i < 3; i++) {
            signalStore.save(ImprovementSignal.create(
                    tenantId, userId, SignalType.REPEAT_PATTERN, "ses_" + i,
                    "Repeat pattern detected", 0.5));
        }

        List<ImprovementProposal> proposals = proposer.detectPatternShift(tenantId, userId);

        assertFalse(proposals.isEmpty());
        assertTrue(proposals.stream().anyMatch(p -> p.title().contains("repeated tasks")));
    }

    @Test
    void proposerDetectsReferencePattern() {
        for (int i = 0; i < 3; i++) {
            signalStore.save(ImprovementSignal.create(
                    tenantId, userId, SignalType.SESSION_REFERENCE, "ses_" + i,
                    "User referenced session", 0.7));
        }

        List<ImprovementProposal> proposals = proposer.detectPatternShift(tenantId, userId);

        assertTrue(proposals.stream().anyMatch(p -> p.title().contains("Reference pattern")));
    }

    @Test
    void proposerDetectsCorrectionIncrease() {
        for (int i = 0; i < 5; i++) {
            signalStore.save(ImprovementSignal.create(
                    tenantId, userId, SignalType.USER_CORRECTION, "ses_" + i,
                    "User correction", 1.0));
        }

        List<ImprovementProposal> proposals = proposer.detectPatternShift(tenantId, userId);

        assertTrue(proposals.stream().anyMatch(p -> p.status() == ProposalStatus.REQUIRE_CONFIRM));
        assertTrue(proposals.stream().anyMatch(p -> p.title().contains("Accuracy concern")));
    }

    @Test
    void proposerNoSignalNoProposal() {
        List<ImprovementProposal> proposals = proposer.detectPatternShift(tenantId, userId);
        assertTrue(proposals.isEmpty());
    }

    @Test
    void proposerRepeatPatternBelowThreshold() {
        signalStore.save(ImprovementSignal.create(
                tenantId, userId, SignalType.REPEAT_PATTERN, "ses_1", "repeat", 0.5));
        signalStore.save(ImprovementSignal.create(
                tenantId, userId, SignalType.REPEAT_PATTERN, "ses_2", "repeat", 0.5));

        // Only 2 signals, threshold is 3
        List<ImprovementProposal> proposals = proposer.detectPatternShift(tenantId, userId);
        assertTrue(proposals.stream().noneMatch(p -> p.title().contains("repeated tasks")));
    }

    // ── PreferenceUpdate record ──

    @Test
    void preferenceUpdateRecord() {
        PreferenceUpdate pu = new PreferenceUpdate(
                "user1", "response_style", "verbose", "concise", 0.85, "5 bookmarks");
        assertEquals("user1", pu.userId());
        assertEquals("response_style", pu.key());
        assertEquals("verbose", pu.oldValue());
        assertEquals("concise", pu.newValue());
        assertEquals(0.85, pu.confidence());
        assertEquals("5 bookmarks", pu.evidence());
    }
}
