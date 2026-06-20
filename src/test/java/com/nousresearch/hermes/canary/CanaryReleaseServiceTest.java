package com.nousresearch.hermes.canary;

import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanaryReleaseServiceTest {

    @TempDir
    Path tempDir;

    private WorkspaceService workspaceService;
    private TeamBlueprintService blueprintService;
    private CanaryReleaseService canaryService;

    @BeforeEach
    void setUp() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), tenantManager);
        workspaceService.createWorkspace("ws1", "WS1", null, "ops", Map.of());

        blueprintService = new TeamBlueprintService(tempDir.resolve("workspaces"), workspaceService);
        canaryService = new CanaryReleaseService(tempDir.resolve("workspaces"), workspaceService, blueprintService);

        // Create team v1
        blueprintService.createTeamBlueprint("ws1", "team1", "Team 1",
            "Test team", "ops", "ops-task",
            List.of(new AgentBlueprintRecord().setAgentId("agent1").setDisplayName("Agent 1")),
            List.of(), "test", Map.of());

        // Add v2
        blueprintService.createDraftVersion("ws1", "team1", "v2 changes",
            List.of(new AgentBlueprintRecord().setAgentId("agent1").setDisplayName("Agent 1 v2")),
            List.of(), "test", Map.of());
    }

    @Test
    void startCanaryFromV1ToV2() {
        var canary = canaryService.startCanary("ws1", "team1", 2, 10, Map.of());
        assertEquals(1, canary.getFromVersion());
        assertEquals(2, canary.getToVersion());
        assertEquals(10, canary.getTrafficPercent());
        assertEquals(CanaryReleaseRecord.ACTIVE, canary.getStatus());
    }

    @Test
    void cannotStartCanaryToSameVersion() {
        // active version is 1
        assertThrows(IllegalArgumentException.class,
            () -> canaryService.startCanary("ws1", "team1", 1, 10, Map.of()));
    }

    @Test
    void cannotStartTwoActiveCanaries() {
        canaryService.startCanary("ws1", "team1", 2, 10, Map.of());
        assertThrows(IllegalStateException.class,
            () -> canaryService.startCanary("ws1", "team1", 2, 50, Map.of()));
    }

    @Test
    void updateTrafficGradually() {
        var canary = canaryService.startCanary("ws1", "team1", 2, 5, Map.of());
        var updated = canaryService.updateTraffic("ws1", "team1", canary.getReleaseId(), 25);
        assertEquals(25, updated.getTrafficPercent());

        updated = canaryService.updateTraffic("ws1", "team1", canary.getReleaseId(), 50);
        assertEquals(50, updated.getTrafficPercent());
    }

    @Test
    void promoteCanary() {
        var canary = canaryService.startCanary("ws1", "team1", 2, 50, Map.of());
        var promoted = canaryService.promote("ws1", "team1", canary.getReleaseId());
        assertEquals(CanaryReleaseRecord.COMPLETED, promoted.getStatus());
        assertEquals(100, promoted.getTrafficPercent());

        // Active version should now be 2
        var team = blueprintService.requireTeamBlueprint("ws1", "team1");
        assertEquals(2, team.getActiveVersion());
    }

    @Test
    void rollbackCanary() {
        var canary = canaryService.startCanary("ws1", "team1", 2, 50, Map.of());
        var rolled = canaryService.rollback("ws1", "team1", canary.getReleaseId());
        assertEquals(CanaryReleaseRecord.ROLLED_BACK, rolled.getStatus());

        // Active version should still be 1
        var team = blueprintService.requireTeamBlueprint("ws1", "team1");
        assertEquals(1, team.getActiveVersion());
    }

    @Test
    void resolveVersionWithoutCanaryReturnsActive() {
        int version = canaryService.resolveVersion("ws1", "team1", "any-key");
        assertEquals(1, version);
    }

    @Test
    void resolveVersionWithCanaryRoutesByHash() {
        canaryService.startCanary("ws1", "team1", 2, 50, Map.of());

        int v1Count = 0, v2Count = 0;
        for (int i = 0; i < 100; i++) {
            int v = canaryService.resolveVersion("ws1", "team1", "request-" + i);
            if (v == 1) v1Count++;
            else if (v == 2) v2Count++;
        }
        // With 50% traffic, expect roughly half routed to v2 (within tolerance)
        assertTrue(v2Count >= 30 && v2Count <= 70,
            "Expected ~50% routing to v2 with 50% traffic, got " + v2Count + "%");
    }

    @Test
    void resolveVersionDeterministicForSameKey() {
        canaryService.startCanary("ws1", "team1", 2, 50, Map.of());

        // Same key → same version
        int v1 = canaryService.resolveVersion("ws1", "team1", "user-123");
        int v2 = canaryService.resolveVersion("ws1", "team1", "user-123");
        int v3 = canaryService.resolveVersion("ws1", "team1", "user-123");
        assertEquals(v1, v2);
        assertEquals(v1, v3);
    }

    @Test
    void recordRunOutcomeTracksMetricsPerVersion() {
        var canary = canaryService.startCanary("ws1", "team1", 2, 50, Map.of());

        // Record 3 successes for canary (v2), 2 failures for baseline (v1)
        canaryService.recordRunOutcome("ws1", "team1", 2, "COMPLETED", 1500, 0.05);
        canaryService.recordRunOutcome("ws1", "team1", 2, "COMPLETED", 1200, 0.04);
        canaryService.recordRunOutcome("ws1", "team1", 2, "COMPLETED", 1800, 0.06);
        canaryService.recordRunOutcome("ws1", "team1", 1, "FAILED", 5000, 0.15);
        canaryService.recordRunOutcome("ws1", "team1", 1, "COMPLETED", 2000, 0.08);

        var refreshed = canaryService.getActiveCanary("ws1", "team1").orElseThrow();
        Map<String, Object> metrics = refreshed.getMetrics();

        assertEquals(3L, ((Number) metrics.get("canaryTotal")).longValue());
        assertEquals(3L, ((Number) metrics.get("canarySucceeded")).longValue());
        assertEquals(0L, ((Number) metrics.get("canaryFailed")).longValue());
        assertEquals(1.0, ((Number) metrics.get("canarySuccessRate")).doubleValue(), 0.001);

        assertEquals(2L, ((Number) metrics.get("baselineTotal")).longValue());
        assertEquals(1L, ((Number) metrics.get("baselineSucceeded")).longValue());
        assertEquals(1L, ((Number) metrics.get("baselineFailed")).longValue());
        assertEquals(0.5, ((Number) metrics.get("baselineSuccessRate")).doubleValue(), 0.001);
    }

    @Test
    void listCanariesIncludesAllStatuses() {
        var c1 = canaryService.startCanary("ws1", "team1", 2, 10, Map.of());
        canaryService.rollback("ws1", "team1", c1.getReleaseId());

        var c2 = canaryService.startCanary("ws1", "team1", 2, 20, Map.of());
        canaryService.promote("ws1", "team1", c2.getReleaseId());

        // After v2 is promoted, can start a canary back to v1 (since v1 is no longer active)
        // Skip — requires a v3 to canary to. Just verify list contains both.

        var list = canaryService.listCanaries("ws1", "team1");
        assertEquals(2, list.size());
    }
}
