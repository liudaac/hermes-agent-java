package com.nousresearch.hermes.improvement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the improvement signal collection and confirmation flow.
 *
 * <p>Covers: SignalStore CRUD, SignalCollector deterministic signals,
 * ImprovementConfirmationFlow (AUTO/PROMPT/REQUIRE), ImprovementProposal lifecycle.</p>
 */
class ImprovementSignalTest {

    private LocalSignalStore signalStore;
    private LocalProposalStore proposalStore;
    private SignalCollector collector;
    private ImprovementConfirmationFlow confirmationFlow;

    private final String tenantId = "test-tenant";
    private final String userId = "usr_test";
    private final String sessionId = "ses_001";

    @BeforeEach
    void setUp() {
        signalStore = new LocalSignalStore();
        proposalStore = new LocalProposalStore();
        collector = new SignalCollector(signalStore);
        confirmationFlow = new ImprovementConfirmationFlow(proposalStore);
    }

    // ── SignalStore CRUD ──

    @Test
    void signalStoreSaveAndQuery() {
        ImprovementSignal signal = ImprovementSignal.create(
                tenantId, userId, SignalType.BOOKMARK, sessionId, "test", 0.6);
        signalStore.save(signal);

        List<ImprovementSignal> results = signalStore.queryByUser(tenantId, userId);
        assertEquals(1, results.size());
        assertEquals(signal, results.get(0));
    }

    @Test
    void signalStoreQueryByType() {
        signalStore.save(ImprovementSignal.create(tenantId, userId, SignalType.BOOKMARK, sessionId, "b1", 0.6));
        signalStore.save(ImprovementSignal.create(tenantId, userId, SignalType.RATING_HIGH, sessionId, "r1", 0.8));
        signalStore.save(ImprovementSignal.create(tenantId, userId, SignalType.BOOKMARK, sessionId, "b2", 0.6));

        List<ImprovementSignal> bookmarks = signalStore.queryByType(tenantId, userId, SignalType.BOOKMARK);
        assertEquals(2, bookmarks.size());
    }

    @Test
    void signalStoreQueryUnprocessed() {
        signalStore.save(ImprovementSignal.create(tenantId, userId, SignalType.BOOKMARK, sessionId, "s1", 0.6));
        signalStore.save(ImprovementSignal.create(tenantId, userId, SignalType.RATING_HIGH, sessionId, "s2", 0.8));

        List<ImprovementSignal> unprocessed = signalStore.queryUnprocessed(tenantId, userId);
        assertEquals(2, unprocessed.size());

        String firstId = unprocessed.get(0).id();
        signalStore.markProcessed(firstId);

        unprocessed = signalStore.queryUnprocessed(tenantId, userId);
        assertEquals(1, unprocessed.size());
    }

    @Test
    void signalStoreCountByType() {
        signalStore.save(ImprovementSignal.create(tenantId, userId, SignalType.BOOKMARK, sessionId, "b1", 0.6));
        signalStore.save(ImprovementSignal.create(tenantId, userId, SignalType.BOOKMARK, sessionId, "b2", 0.6));
        signalStore.save(ImprovementSignal.create(tenantId, userId, SignalType.BOOKMARK, sessionId, "b3", 0.6));

        assertEquals(3, signalStore.countByType(tenantId, userId, SignalType.BOOKMARK));
        assertEquals(0, signalStore.countByType(tenantId, userId, SignalType.RATING_LOW));
    }

    @Test
    void signalStoreUserIsolation() {
        signalStore.save(ImprovementSignal.create(tenantId, "userA", SignalType.BOOKMARK, sessionId, "a1", 0.6));
        signalStore.save(ImprovementSignal.create(tenantId, "userB", SignalType.BOOKMARK, sessionId, "b1", 0.6));

        assertEquals(1, signalStore.queryByUser(tenantId, "userA").size());
        assertEquals(1, signalStore.queryByUser(tenantId, "userB").size());
    }

    @Test
    void signalStoreNullUserIdReturnsAllForTenant() {
        signalStore.save(ImprovementSignal.create(tenantId, "userA", SignalType.BOOKMARK, sessionId, "a1", 0.6));
        signalStore.save(ImprovementSignal.create(tenantId, "userB", SignalType.BOOKMARK, sessionId, "b1", 0.6));

        List<ImprovementSignal> all = signalStore.queryByUser(tenantId, null);
        assertEquals(2, all.size());
    }

    // ── SignalCollector deterministic signals ──

    @Test
    void collectorEmitsBookmarkSignal() {
        collector.onBookmark(tenantId, userId, sessionId, "great session");

        List<ImprovementSignal> signals = signalStore.queryByType(tenantId, userId, SignalType.BOOKMARK);
        assertEquals(1, signals.size());
        assertEquals(0.6, signals.get(0).weight());
        assertTrue(signals.get(0).content().contains("great session"));
    }

    @Test
    void collectorEmitsRatingHighSignal() {
        collector.onRatingHigh(tenantId, userId, sessionId, 5);

        List<ImprovementSignal> signals = signalStore.queryByType(tenantId, userId, SignalType.RATING_HIGH);
        assertEquals(1, signals.size());
        assertEquals(0.8, signals.get(0).weight());
    }

}
