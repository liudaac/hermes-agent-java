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

    @Test
    void collectorEmitsRatingLowSignal() {
        collector.onRatingLow(tenantId, userId, sessionId, 1);

        List<ImprovementSignal> signals = signalStore.queryByType(tenantId, userId, SignalType.RATING_LOW);
        assertEquals(1, signals.size());
        assertEquals(1.0, signals.get(0).weight());
    }

    @Test
    void collectorEmitsSessionReferenceSignal() {
        collector.onSessionReference(tenantId, userId, "ref_ses_001");

        List<ImprovementSignal> signals = signalStore.queryByType(tenantId, userId, SignalType.SESSION_REFERENCE);
        assertEquals(1, signals.size());
        assertEquals(0.7, signals.get(0).weight());
        assertTrue(signals.get(0).content().contains("ref_ses_001"));
    }

    @Test
    void collectorEmitsMultipleSignals() {
        collector.onBookmark(tenantId, userId, sessionId, null);
        collector.onBookmark(tenantId, userId, "ses_002", null);
        collector.onRatingHigh(tenantId, userId, sessionId, 4);
        collector.onSessionReference(tenantId, userId, "ses_003");

        List<ImprovementSignal> all = signalStore.queryByUser(tenantId, userId);
        assertEquals(4, all.size());
        assertEquals(2, signalStore.countByType(tenantId, userId, SignalType.BOOKMARK));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.RATING_HIGH));
        assertEquals(1, signalStore.countByType(tenantId, userId, SignalType.SESSION_REFERENCE));
    }

    // ── ImprovementProposal lifecycle ──

    @Test
    void proposalCreateAndFind() {
        ImprovementProposal proposal = ImprovementProposal.pending(
                tenantId, userId, "Test Proposal", "Finding", "Change", "Benefit", "evidence", 0.8);
        proposalStore.save(proposal);

        ImprovementProposal found = proposalStore.findById(tenantId, proposal.id());
        assertNotNull(found);
        assertEquals("Test Proposal", found.title());
        assertEquals(ProposalStatus.PENDING, found.status());
    }

    @Test
    void proposalQueryPending() {
        proposalStore.save(ImprovementProposal.pending(tenantId, userId, "P1", "f", "c", "b", "e", 0.7));
        proposalStore.save(ImprovementProposal.requireConfirm(tenantId, userId, "P2", "f", "c", "b", "e", 0.9));
        proposalStore.save(ImprovementProposal.autoApplied(tenantId, userId, "P3", "f", "c", "b", "e", 0.5));

        List<ImprovementProposal> pending = proposalStore.queryPending(tenantId, userId);
        assertEquals(2, pending.size());
    }

    @Test
    void proposalUserIsolation() {
        proposalStore.save(ImprovementProposal.pending(tenantId, "userA", "P1", "f", "c", "b", "e", 0.7));
        proposalStore.save(ImprovementProposal.pending(tenantId, "userB", "P2", "f", "c", "b", "e", 0.7));

        assertEquals(1, proposalStore.queryByUser(tenantId, "userA").size());
        assertEquals(1, proposalStore.queryByUser(tenantId, "userB").size());
    }

    // ── ImprovementConfirmationFlow ──

    @Test
    void flowAutoApply() {
        ImprovementProposal input = ImprovementProposal.pending(
                tenantId, userId, "Auto", "f", "c", "b", "e", 0.5);
        ImprovementProposal result = confirmationFlow.autoApply(input);

        assertEquals(ProposalStatus.AUTO_APPLIED, result.status());
        assertNotNull(result.resolvedAt());
        assertNotNull(proposalStore.findById(tenantId, result.id()));
    }

    @Test
    void flowProposePending() {
        ImprovementProposal input = ImprovementProposal.pending(
                tenantId, userId, "Pending", "f", "c", "b", "e", 0.7);
        ImprovementProposal result = confirmationFlow.proposePending(input);

        assertEquals(ProposalStatus.PENDING, result.status());
        assertNull(result.resolvedAt());
    }

    @Test
    void flowRequireConfirm() {
        ImprovementProposal input = ImprovementProposal.pending(
                tenantId, userId, "Require", "f", "c", "b", "e", 0.9);
        ImprovementProposal result = confirmationFlow.requireConfirm(input);

        assertEquals(ProposalStatus.REQUIRE_CONFIRM, result.status());
    }

    @Test
    void flowAcceptPendingProposal() {
        ImprovementProposal pending = confirmationFlow.proposePending(
                ImprovementProposal.pending(tenantId, userId, "P1", "f", "c", "b", "e", 0.7));

        ImprovementProposal accepted = confirmationFlow.accept(tenantId, pending.id());

        assertNotNull(accepted);
        assertEquals(ProposalStatus.APPLIED, accepted.status());
        assertNotNull(accepted.resolvedAt());
    }

    @Test
    void flowAcceptRequireConfirmProposal() {
        ImprovementProposal req = confirmationFlow.requireConfirm(
                ImprovementProposal.pending(tenantId, userId, "P1", "f", "c", "b", "e", 0.9));

        ImprovementProposal accepted = confirmationFlow.accept(tenantId, req.id());

        assertNotNull(accepted);
        assertEquals(ProposalStatus.APPLIED, accepted.status());
    }

    @Test
    void flowRejectProposal() {
        ImprovementProposal pending = confirmationFlow.proposePending(
                ImprovementProposal.pending(tenantId, userId, "P1", "f", "c", "b", "e", 0.7));

        ImprovementProposal rejected = confirmationFlow.reject(tenantId, pending.id());

        assertNotNull(rejected);
        assertEquals(ProposalStatus.REJECTED, rejected.status());
    }

    @Test
    void flowAcceptNotFoundReturnsNull() {
        assertNull(confirmationFlow.accept(tenantId, "nonexistent"));
    }

    @Test
    void flowAcceptAlreadyAppliedReturnsNull() {
        ImprovementProposal auto = confirmationFlow.autoApply(
                ImprovementProposal.pending(tenantId, userId, "P1", "f", "c", "b", "e", 0.5));

        // Already applied, can't accept again
        assertNull(confirmationFlow.accept(tenantId, auto.id()));
    }

    @Test
    void flowRejectNotFoundReturnsNull() {
        assertNull(confirmationFlow.reject(tenantId, "nonexistent"));
    }

    // ── ImprovementSignal factory ──

    @Test
    void signalCreateGeneratesId() {
        ImprovementSignal s1 = ImprovementSignal.create(tenantId, userId, SignalType.BOOKMARK, sessionId, "c", 0.6);
        ImprovementSignal s2 = ImprovementSignal.create(tenantId, userId, SignalType.BOOKMARK, sessionId, "c", 0.6);

        assertNotEquals(s1.id(), s2.id());
        assertFalse(s1.processed());
    }

    @Test
    void signalMarkProcessed() {
        ImprovementSignal s = ImprovementSignal.create(tenantId, userId, SignalType.BOOKMARK, sessionId, "c", 0.6);
        ImprovementSignal processed = s.markProcessed();

        assertTrue(processed.processed());
        assertFalse(s.processed()); // original unchanged (record)
        assertEquals(s.id(), processed.id());
    }

    // ── ImprovementProposal factory methods ──

    @Test
    void proposalPendingFactory() {
        ImprovementProposal p = ImprovementProposal.pending(tenantId, userId, "T", "f", "c", "b", "e", 0.7);
        assertEquals(ProposalStatus.PENDING, p.status());
        assertNull(p.resolvedAt());
    }

    @Test
    void proposalRequireConfirmFactory() {
        ImprovementProposal p = ImprovementProposal.requireConfirm(tenantId, userId, "T", "f", "c", "b", "e", 0.9);
        assertEquals(ProposalStatus.REQUIRE_CONFIRM, p.status());
    }

    @Test
    void proposalAutoAppliedFactory() {
        ImprovementProposal p = ImprovementProposal.autoApplied(tenantId, userId, "T", "f", "c", "b", "e", 0.5);
        assertEquals(ProposalStatus.AUTO_APPLIED, p.status());
        assertNotNull(p.resolvedAt());
    }

    @Test
    void proposalWithStatus() {
        ImprovementProposal p = ImprovementProposal.pending(tenantId, userId, "T", "f", "c", "b", "e", 0.7);
        ImprovementProposal applied = p.withStatus(ProposalStatus.APPLIED);

        assertEquals(ProposalStatus.PENDING, p.status()); // original unchanged
        assertEquals(ProposalStatus.APPLIED, applied.status());
        assertNotNull(applied.resolvedAt());
    }
}
