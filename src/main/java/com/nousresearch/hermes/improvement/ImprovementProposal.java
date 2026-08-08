package com.nousresearch.hermes.improvement;

/**
 * An improvement proposal generated from accumulated signals.
 *
 * <p>Proposals are created by the self-improvement engine when signals
 * reach a threshold. They follow a tiered confirmation model:</p>
 * <ul>
 *   <li>AUTO_APPLIED - low risk, applied automatically</li>
 *   <li>PENDING - user can review at their convenience (PROMPT)</li>
 *   <li>REQUIRE_CONFIRM - user must confirm before applying (REQUIRE)</li>
 * </ul>
 *
 * @param id             unique proposal ID
 * @param tenantId       tenant
 * @param userId         user
 * @param title          short title
 * @param finding        what was observed (e.g. "User bookmarked 5 sessions about deployment")
 * @param proposedChange what to change (e.g. "Prefer terminal-based deployment flow")
 * @param expectedBenefit expected outcome (e.g. "Faster deployment tasks")
 * @param status         current status
 * @param evidence       supporting evidence string (signal summary)
 * @param confidence     0.0-1.0
 * @param createdAt      epoch millis
 * @param resolvedAt     epoch millis (nullable)
 */
public record ImprovementProposal(
        String id,
        String tenantId,
        String userId,
        String title,
        String finding,
        String proposedChange,
        String expectedBenefit,
        ProposalStatus status,
        String evidence,
        double confidence,
        long createdAt,
        Long resolvedAt
) {
    /**
     * Creates a new pending proposal with a generated ID and current timestamp.
     */
    public static ImprovementProposal pending(String tenantId, String userId,
                                               String title, String finding,
                                               String proposedChange, String expectedBenefit,
                                               String evidence, double confidence) {
        return new ImprovementProposal(
                "prop_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                tenantId, userId, title, finding, proposedChange, expectedBenefit,
                ProposalStatus.PENDING, evidence, confidence,
                System.currentTimeMillis(), null
        );
    }

    /**
     * Creates a require-confirm proposal.
     */
    public static ImprovementProposal requireConfirm(String tenantId, String userId,
                                                      String title, String finding,
                                                      String proposedChange, String expectedBenefit,
                                                      String evidence, double confidence) {
        return new ImprovementProposal(
                "prop_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                tenantId, userId, title, finding, proposedChange, expectedBenefit,
                ProposalStatus.REQUIRE_CONFIRM, evidence, confidence,
                System.currentTimeMillis(), null
        );
    }

    /**
     * Creates an auto-applied proposal.
     */
    public static ImprovementProposal autoApplied(String tenantId, String userId,
                                                   String title, String finding,
                                                   String proposedChange, String expectedBenefit,
                                                   String evidence, double confidence) {
        return new ImprovementProposal(
                "prop_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                tenantId, userId, title, finding, proposedChange, expectedBenefit,
                ProposalStatus.AUTO_APPLIED, evidence, confidence,
                System.currentTimeMillis(), System.currentTimeMillis()
        );
    }

    /**
     * Returns a copy with a new status and resolvedAt.
     */
    public ImprovementProposal withStatus(ProposalStatus newStatus) {
        return new ImprovementProposal(id, tenantId, userId, title, finding,
                proposedChange, expectedBenefit, newStatus, evidence, confidence,
                createdAt, System.currentTimeMillis());
    }
}
