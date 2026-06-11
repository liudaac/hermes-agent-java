package com.nousresearch.hermes.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.impl.OrgNativeTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextAwareDelegationTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    private TenantContext tenant;

    @BeforeEach
    void setUp() {
        String tenantId = "delegation-tenant-" + System.nanoTime();
        tenant = TenantContext.create(tenantId, TenantProvisioningRequest.builder(tenantId, "test-user").build());
        tenant.registerAgentRole("agent-1",
            new AgentRole("release-manager", "Manages releases", AgentRole.Level.LEAD)
                .skills("release", "deployment", "critical"));
        tenant.registerAgentRole("agent-2",
            new AgentRole("code-reviewer", "Reviews code", AgentRole.Level.SENIOR)
                .skills("java", "review"));
    }

    @Test
    void normalTaskWithoutSignalsDoesNotRecommendDelegation() {
        var plan = tenant.getIntentOrchestrator().plan("review code", null, true, List.of());
        var map = plan.toMap();

        assertEquals(false, map.get("delegation_recommended"));
        assertEquals("LOW", ((Map<?, ?>) map.get("context_pressure")).get("level"));
        assertTrue(plan.assignments().stream().noneMatch(a -> a.delegationDecision() != null && a.delegationDecision().recommended()));
    }

    @Test
    void compactedCriticalPathSignalsRecommendDelegation() {
        var plan = tenant.getIntentOrchestrator().plan(
            "deploy critical release",
            null,
            true,
            List.of("compacted", "critical_path")
        );
        var map = plan.toMap();

        assertEquals(true, map.get("delegation_recommended"));
        assertNotNull(map.get("delegation_reason"));
        var pressure = (Map<?, ?>) map.get("context_pressure");
        assertEquals("CRITICAL", pressure.get("level"));
        assertEquals(true, pressure.get("compacted"));
        assertEquals(true, pressure.get("critical_path"));
        assertEquals("critical-path-reviewer", map.get("suggested_profile"));
        assertTrue(plan.assignments().stream().allMatch(a -> Boolean.TRUE.equals(a.toMap().get("delegation_recommended"))));
    }

    @Test
    void delegationRecommendsTeamAndProfile() {
        var team = tenant.getTeamManager().createTeam("release", "Release Team", "Ship safely", "test");
        team.addMember("agent-1");

        var plan = tenant.getIntentOrchestrator().plan(
            "deploy critical release",
            "release",
            true,
            List.of("compacted", "critical_path")
        );
        var map = plan.toMap();

        assertEquals(true, map.get("delegation_recommended"));
        assertEquals("release", map.get("suggested_team_id"));
        assertEquals("critical-path-reviewer", map.get("suggested_profile"));
        assertTrue(map.containsKey("delegated_task_envelope"));
    }

    @Test
    void orchestrateIntentArgsAndMetadataFallbackExposeDelegation() throws Exception {
        ToolRegistry registry = ToolRegistry.getInstance();
        OrgNativeTools.register(registry);
        TenantAwareToolDispatcher dispatcher = new TenantAwareToolDispatcher(tenant, registry);

        String result = dispatcher.dispatch("orchestrate_intent", Map.of(
            "intent", "deploy critical release",
            "mode", "plan",
            "metadata", Map.of(
                "allow_delegation", true,
                "context_signals", List.of("compacted", "critical_path")
            )
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> json = new ObjectMapper().readValue(result, Map.class);
        assertEquals(true, json.get("delegation_recommended"));
        assertEquals("critical-path-reviewer", json.get("suggested_profile"));
        assertNotNull(json.get("context_pressure"));
    }
}
