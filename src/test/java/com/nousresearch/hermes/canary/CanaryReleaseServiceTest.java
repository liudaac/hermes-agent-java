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

}
