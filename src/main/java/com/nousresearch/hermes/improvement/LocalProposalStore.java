package com.nousresearch.hermes.improvement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory proposal store for local / single-node mode.
 *
 * <p>Sprint 6 will add PostgresProposalStore.</p>
 */
public class LocalProposalStore implements ProposalStore {

    private static final Logger logger = LoggerFactory.getLogger(LocalProposalStore.class);

    // tenantId -> proposals
    private final Map<String, List<ImprovementProposal>> store = new ConcurrentHashMap<>();

    @Override
    public void save(ImprovementProposal proposal) {
        store.computeIfAbsent(proposal.tenantId(), k -> new CopyOnWriteArrayList<>())
             .add(proposal);
        logger.debug("Saved proposal: title={}, status={}, confidence={}",
                     proposal.title(), proposal.status(), proposal.confidence());
    }

    @Override
    public ImprovementProposal findById(String tenantId, String proposalId) {
        return store.getOrDefault(tenantId, List.of()).stream()
                .filter(p -> p.id().equals(proposalId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<ImprovementProposal> queryPending(String tenantId, String userId) {
        return queryByUser(tenantId, userId).stream()
                .filter(p -> p.status() == ProposalStatus.PENDING || p.status() == ProposalStatus.REQUIRE_CONFIRM)
                .collect(Collectors.toList());
    }

    @Override
    public List<ImprovementProposal> queryByUser(String tenantId, String userId) {
        List<ImprovementProposal> proposals = store.getOrDefault(tenantId, List.of());
        if (userId == null) {
            return new ArrayList<>(proposals);
        }
        return proposals.stream()
                .filter(p -> userId.equals(p.userId()))
                .collect(Collectors.toList());
    }

    @Override
    public void update(ImprovementProposal proposal) {
        List<ImprovementProposal> proposals = store.get(proposal.tenantId());
        if (proposals == null) return;
        for (int i = 0; i < proposals.size(); i++) {
            if (proposals.get(i).id().equals(proposal.id())) {
                proposals.set(i, proposal);
                return;
            }
        }
    }

    @Override
    public List<ImprovementProposal> queryAll(String tenantId) {
        return new ArrayList<>(store.getOrDefault(tenantId, List.of()));
    }

    /**
     * Clears all proposals for testing.
     */
    public void clear() {
        store.clear();
    }
}
