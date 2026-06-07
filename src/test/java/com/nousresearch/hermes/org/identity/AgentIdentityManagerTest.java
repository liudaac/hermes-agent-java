package com.nousresearch.hermes.org.identity;

import com.nousresearch.hermes.collaboration.AgentRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentIdentityManagerTest {

    private AgentIdentityManager manager;

    @BeforeEach
    void setUp() {
        manager = new AgentIdentityManager();
    }

    @Test
    void testProvisionCreatesIdentityWithApiKey() {
        AgentRole role = new AgentRole("tester", "Test agent", AgentRole.Level.JUNIOR)
            .skills("java", "testing")
            .allowedTools("file:read", "web:search");

        AgentIdentity id = manager.provision("agent-001", "TestAgent", role);

        assertNotNull(id);
        assertEquals("agent-001", id.getAgentId());
        assertEquals("TestAgent", id.getDisplayName());
        assertEquals("tester", id.getRole().getRoleName());
        assertTrue(id.isActive());
        assertFalse(id.getCredentials().isEmpty());
    }

    @Test
    void testProvisionDuplicateFails() {
        AgentRole role = new AgentRole("tester", "Test", AgentRole.Level.JUNIOR);
        manager.provision("agent-002", "First", role);
        assertThrows(IllegalArgumentException.class, () ->
            manager.provision("agent-002", "Second", role));
    }

    @Test
    void testRotateCredentialsGeneratesNewKey() {
        AgentRole role = new AgentRole("tester", "Test", AgentRole.Level.JUNIOR);
        AgentIdentity id = manager.provision("agent-003", "Rotator", role);
        AgentCredential oldSigning = id.getSigningCredential();
        assertNotNull(oldSigning);

        AgentCredential fresh = manager.rotateCredentials("agent-003", "admin", "scheduled rotation");

        assertNotNull(fresh);
        assertNotEquals(oldSigning.getHash(), fresh.getHash());
        assertSame(AgentCredential.Status.REVOKED, oldSigning.getStatus());
        assertSame(AgentCredential.Status.ACTIVE, fresh.getStatus());
    }

    @Test
    void testDeactivateRevokesAllCredentials() {
        AgentRole role = new AgentRole("tester", "Test", AgentRole.Level.JUNIOR);
        AgentIdentity id = manager.provision("agent-004", "Deactivator", role);
        manager.deactivate("agent-004", "admin", "no longer needed");

        assertFalse(id.isActive());
        for (AgentCredential c : id.getCredentials().values()) {
            assertEquals(AgentCredential.Status.REVOKED, c.getStatus());
        }
    }

    @Test
    void testFindByRole() {
        AgentRole testerRole = new AgentRole("tester", "Test", AgentRole.Level.JUNIOR);
        AgentRole reviewerRole = new AgentRole("reviewer", "Review", AgentRole.Level.SENIOR);
        manager.provision("agent-a", "Alice", testerRole);
        manager.provision("agent-b", "Bob", reviewerRole);

        List<AgentIdentity> testers = manager.findByRole("tester");
        assertEquals(1, testers.size());
        assertEquals("agent-a", testers.get(0).getAgentId());

        List<AgentIdentity> reviewers = manager.findByRole("reviewer");
        assertEquals(1, reviewers.size());
        assertEquals("agent-b", reviewers.get(0).getAgentId());
    }

    @Test
    void testFindByDepartment() {
        AgentRole role = new AgentRole("eng", "Engineer", AgentRole.Level.MID);
        AgentIdentity id = manager.provision("agent-eng", "EngineerBot", role)
            .department("engineering");
        manager.provision("agent-ops", "OpsBot", new AgentRole("ops", "Ops", AgentRole.Level.MID))
            .department("operations");

        List<AgentIdentity> engineers = manager.findByDepartment("engineering");
        assertEquals(1, engineers.size());
        assertEquals("agent-eng", engineers.get(0).getAgentId());
    }

    @Test
    void testGetWarningsDetectsExpiringCredentials() {
        // Create a credential that expires in 12 hours
        AgentRole role = new AgentRole("test", "Test", AgentRole.Level.JUNIOR);
        AgentIdentity id = new AgentIdentity("agent-warn", "WarnBot", role);
        AgentCredential expiring = AgentCredential.generateApiKey(Instant.now().plusSeconds(12 * 3600));
        id.issueCredential(expiring);

        List<String> warnings = id.getWarnings();
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("expiring")));
    }

    @Test
    void testSummaryCorrect() {
        AgentRole role = new AgentRole("test", "Test", AgentRole.Level.JUNIOR);
        manager.provision("a1", "A", role);
        manager.provision("a2", "B", role);
        manager.deactivate("a2", "admin", "test");

        var summary = manager.getSummary();
        assertEquals(2, (int) summary.get("total_identities"));
        assertEquals(1, (int) summary.get("active"));
    }

    @Test
    void testCredentialLifecycle() {
        AgentCredential key = AgentCredential.generateApiKey(Instant.now().plusSeconds(3600));
        assertEquals(AgentCredential.Status.ACTIVE, key.getStatus());

        key.markRevoked("admin", "compromised");
        assertEquals(AgentCredential.Status.REVOKED, key.getStatus());
        assertEquals("admin", key.getRevokedBy());
        assertEquals("compromised", key.getRevocationReason());
    }

    @Test
    void testSignMessage() {
        AgentRole role = new AgentRole("test", "Test", AgentRole.Level.JUNIOR);
        AgentIdentity id = new AgentIdentity("signer", "Signer", role);
        AgentCredential key = AgentCredential.generateApiKey(Instant.now().plusSeconds(3600));
        id.issueCredential(key);

        String signature = id.signMessage("hello world", "nonce123");
        assertNotNull(signature);
        assertFalse(signature.isBlank());
    }
}
