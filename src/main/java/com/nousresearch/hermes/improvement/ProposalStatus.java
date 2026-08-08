package com.nousresearch.hermes.improvement;

/**
 * Status of an improvement proposal.
 */
public enum ProposalStatus {
    /** Created, waiting for user to review (PROMPT mode) */
    PENDING,
    /** Requires explicit user confirmation before applying (REQUIRE mode) */
    REQUIRE_CONFIRM,
    /** User accepted the proposal, applied */
    APPLIED,
    /** User rejected the proposal */
    REJECTED,
    /** Auto-applied (AUTO mode, low risk) */
    AUTO_APPLIED
}
