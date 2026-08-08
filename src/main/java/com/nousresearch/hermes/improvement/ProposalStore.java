package com.nousresearch.hermes.improvement;

import java.util.List;

/**
 * Storage interface for improvement proposals.
 */
public interface ProposalStore {

    void save(ImprovementProposal proposal);

    ImprovementProposal findById(String tenantId, String proposalId);

    List<ImprovementProposal> queryPending(String tenantId, String userId);

    List<ImprovementProposal> queryByUser(String tenantId, String userId);

    List<ImprovementProposal> queryAll(String tenantId);

    void update(ImprovementProposal proposal);
}
