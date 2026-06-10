package com.nousresearch.hermes.tenant.core;

import com.nousresearch.hermes.collaboration.AgentRole;
import com.nousresearch.hermes.collaboration.CapabilityScorer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantAgentRolePersistenceTest {

    @Test
    void agentRoleMetricsPersistAcrossTenantReload() {
        String tenantId = "role-persist-" + System.nanoTime();
        var request = TenantProvisioningRequest.builder(tenantId, "test-user").build();
        TenantContext created = TenantContext.create(tenantId, request);
        created.registerAgentRole("agent-qa", new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.SENIOR)
            .skills("qa", "tests")
            .deprioritize(1.5));
        created.save();

        TenantContext loaded = TenantContext.load(tenantId);
        AgentRole role = loaded.getAgentRole("agent-qa");

        assertNotNull(role);
        assertEquals("qa-engineer", role.getRoleName());
        assertEquals(1.5, ((Number) role.getMetrics().get("manual_penalty")).doubleValue(), 0.001);
        var score = CapabilityScorer.score("run tests", "agent-qa", role, loaded);
        assertEquals(-1.5, score.components().get("manual_override"), 0.001);
    }
}
