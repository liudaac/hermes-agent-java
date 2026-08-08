package com.nousresearch.hermes.improvement;

import javax.sql.DataSource;

/**
 * Factory for creating the appropriate SignalStore and ProposalStore
 * based on available infrastructure.
 *
 * <p>Priority: Postgres > Redis > Local</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * DataSource ds = ...; // null if no DB
 * SignalStore signalStore = ImprovementStoreFactory.createSignalStore(ds);
 * ProposalStore proposalStore = ImprovementStoreFactory.createProposalStore(ds);
 * }</pre>
 */
public class ImprovementStoreFactory {

    private static SignalStore signalStore;
    private static ProposalStore proposalStore;

    /**
     * Create or return the cached SignalStore.
     * Postgres if DataSource available, else Local.
     */
    public static synchronized SignalStore createSignalStore(DataSource dataSource) {
        if (signalStore != null) return signalStore;
        if (dataSource != null) {
            signalStore = new PostgresSignalStore(dataSource);
        } else {
            signalStore = new LocalSignalStore();
        }
        return signalStore;
    }

    /**
     * Create or return the cached ProposalStore.
     */
    public static synchronized ProposalStore createProposalStore(DataSource dataSource) {
        if (proposalStore != null) return proposalStore;
        if (dataSource != null) {
            proposalStore = new PostgresProposalStore(dataSource);
        } else {
            proposalStore = new LocalProposalStore();
        }
        return proposalStore;
    }

    /** Get the current SignalStore (null if not created). */
    public static SignalStore getSignalStore() { return signalStore; }

    /** Get the current ProposalStore (null if not created). */
    public static ProposalStore getProposalStore() { return proposalStore; }

    /** Set a custom SignalStore (for testing). */
    public static void setSignalStore(SignalStore store) { signalStore = store; }

    /** Set a custom ProposalStore (for testing). */
    public static void setProposalStore(ProposalStore store) { proposalStore = store; }

    /** Reset to null (for testing). */
    public static void reset() {
        signalStore = null;
        proposalStore = null;
    }
}
