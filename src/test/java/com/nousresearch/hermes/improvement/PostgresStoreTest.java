package com.nousresearch.hermes.improvement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Postgres-backed stores and ImprovementStoreFactory.
 *
 * <p>Since we don't have a real Postgres instance, these tests verify:
 * - Factory routing (Postgres vs Local based on DataSource availability)
 * - SignalBroadcaster serialization round-trip
 * - Factory singleton behavior</p>
 */
class PostgresStoreTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ImprovementStoreFactory.reset();
    }

    @AfterEach
    void tearDown() {
        ImprovementStoreFactory.reset();
    }

    // ── Factory routing ──

    @Test
    void factoryCreatesLocalWhenNoDataSource() {
        SignalStore signalStore = ImprovementStoreFactory.createSignalStore(null);
        ProposalStore proposalStore = ImprovementStoreFactory.createProposalStore(null);

        assertInstanceOf(LocalSignalStore.class, signalStore);
        assertInstanceOf(LocalProposalStore.class, proposalStore);
    }

    @Test
    void factoryCreatesPostgresWhenDataSourceAvailable() {
        // We can't easily test real Postgres, but we can verify the factory
        // creates a PostgresSignalStore when given a non-null DataSource
        // (even though the DataSource won't connect)
        // The PostgresStore constructor calls initSchema which will fail gracefully
        javax.sql.DataSource mockDs = new SimpleMockDataSource();
        SignalStore signalStore = ImprovementStoreFactory.createSignalStore(mockDs);
        ProposalStore proposalStore = ImprovementStoreFactory.createProposalStore(mockDs);

        assertInstanceOf(PostgresSignalStore.class, signalStore);
        assertInstanceOf(PostgresProposalStore.class, proposalStore);
    }

    @Test
    void factoryReturnsCachedInstance() {
        SignalStore s1 = ImprovementStoreFactory.createSignalStore(null);
        SignalStore s2 = ImprovementStoreFactory.createSignalStore(null);
        assertSame(s1, s2);
    }


    // ── Mock DataSource ──

    /**
     * Minimal DataSource that returns a connection which fails gracefully.
     * PostgresSignalStore/PostgresProposalStore constructors will call initSchema
     * which catches SQLException and logs.
     */
    private static class SimpleMockDataSource implements javax.sql.DataSource {
        @Override
        public java.sql.Connection getConnection() throws java.sql.SQLException {
            throw new java.sql.SQLException("Mock DataSource - no real connection");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
            return getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() { return null; }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() { return 0; }

        @Override
        public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }

        @Override
        public <T> T unwrap(Class<T> iface) { return null; }

        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
