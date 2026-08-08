package com.nousresearch.hermes.improvement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight confirmation flow for improvement proposals.
 *
 * <p>Does NOT reuse ApprovalSystem (which is a tool-call safety gate for
 * terminal/file_write/browser etc). SelfImprovement has its own flow:</p>
 *
 * <ul>
 *   <li>AUTO - applied directly, no confirmation needed</li>
 *   <li>PROMPT - written to ProposalStore as PENDING, user reviews in Portal</li>
 *   <li>REQUIRE - written as REQUIRE_CONFIRM, also pushed to IM for explicit y/n</li>
 * </ul>
 */
public class ImprovementConfirmationFlow {

    private static final Logger logger = LoggerFactory.getLogger(ImprovementConfirmationFlow.class);

    private final ProposalStore proposalStore;

    public ImprovementConfirmationFlow(ProposalStore proposalStore) {
        this.proposalStore = proposalStore;
    }

    /**
     * Auto-apply a proposal (LOW risk, AUTO mode).
     * The proposal is saved with status AUTO_APPLIED.
     *
     * @return the saved proposal
     */
    public ImprovementProposal autoApply(ImprovementProposal proposal) {
        ImprovementProposal auto = ImprovementProposal.autoApplied(
                proposal.tenantId(), proposal.userId(),
                proposal.title(), proposal.finding(),
                proposal.proposedChange(), proposal.expectedBenefit(),
                proposal.evidence(), proposal.confidence()
        );
        proposalStore.save(auto);
        logger.info("Auto-applied proposal: {} (confidence={})", auto.title(), auto.confidence());
        return auto;
    }

    /**
     * Submit a pending proposal (MEDIUM risk, PROMPT mode).
     * User can review in Portal at their convenience.
     *
     * @return the saved proposal
     */
    public ImprovementProposal proposePending(ImprovementProposal proposal) {
        ImprovementProposal pending = ImprovementProposal.pending(
                proposal.tenantId(), proposal.userId(),
                proposal.title(), proposal.finding(),
                proposal.proposedChange(), proposal.expectedBenefit(),
                proposal.evidence(), proposal.confidence()
        );
        proposalStore.save(pending);
        logger.info("Pending proposal submitted: {} (confidence={})", pending.title(), pending.confidence());
        return pending;
    }

    /**
     * Submit a proposal requiring explicit confirmation (HIGH risk, REQUIRE mode).
     * User must accept before it's applied.
     *
     * @return the saved proposal
     */
    public ImprovementProposal requireConfirm(ImprovementProposal proposal) {
        ImprovementProposal req = ImprovementProposal.requireConfirm(
                proposal.tenantId(), proposal.userId(),
                proposal.title(), proposal.finding(),
                proposal.proposedChange(), proposal.expectedBenefit(),
                proposal.evidence(), proposal.confidence()
        );
        proposalStore.save(req);
        logger.info("Require-confirm proposal submitted: {} (confidence={})", req.title(), req.confidence());
        return req;
    }

    /**
     * User accepts a pending/require-confirm proposal.
     *
     * @return the updated proposal (status=APPLIED), or null if not found
     */
    public ImprovementProposal accept(String tenantId, String proposalId) {
        ImprovementProposal proposal = proposalStore.findById(tenantId, proposalId);
        if (proposal == null) {
            logger.warn("Proposal not found: {}/{}", tenantId, proposalId);
            return null;
        }
        if (proposal.status() != ProposalStatus.PENDING && proposal.status() != ProposalStatus.REQUIRE_CONFIRM) {
            logger.warn("Proposal {} is not in a confirmable state: {}", proposalId, proposal.status());
            return null;
        }
        ImprovementProposal applied = proposal.withStatus(ProposalStatus.APPLIED);
        proposalStore.update(applied);
        logger.info("Proposal accepted: {}", proposal.title());
        return applied;
    }

    /**
     * User rejects a pending/require-confirm proposal.
     *
     * @return the updated proposal (status=REJECTED), or null if not found
     */
    public ImprovementProposal reject(String tenantId, String proposalId) {
        ImprovementProposal proposal = proposalStore.findById(tenantId, proposalId);
        if (proposal == null) {
            logger.warn("Proposal not found: {}/{}", tenantId, proposalId);
            return null;
        }
        ImprovementProposal rejected = proposal.withStatus(ProposalStatus.REJECTED);
        proposalStore.update(rejected);
        logger.info("Proposal rejected: {}", proposal.title());
        return rejected;
    }
}
