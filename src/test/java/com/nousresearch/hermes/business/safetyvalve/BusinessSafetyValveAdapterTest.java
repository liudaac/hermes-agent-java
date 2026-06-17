package com.nousresearch.hermes.business.safetyvalve;

import com.nousresearch.hermes.blueprint.TeamBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintVersion;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessSafetyValveAdapterTest {

    @Test
    void replayRequestProducesApprovalAndDelegatedEnvelope() {
        BusinessSafetyValveAdapter adapter = new BusinessSafetyValveAdapter();
        BusinessRunRecord run = new BusinessRunRecord()
            .setRunId("run-1")
            .setWorkspaceId("customer-service")
            .setTeamId("after-sales")
            .setTechnicalTraceRef("intent://run-1");

        var projection = adapter.toReplayRequest(run, "ops");

        assertEquals("customer-service", projection.workspaceId());
        assertEquals(BusinessSafetyValveAdapter.ValveType.REPLAY, projection.valveType());
        assertEquals("foundation:safety-valve", projection.metadata().get("source"));
        assertTrue(projection.approvalRequest().getOperation().contains("replay:run-1"));
        assertFalse(projection.approvalRequest().isDangerous());
        assertTrue(projection.delegatedTaskEnvelope().runId().contains("safety-valve:replay:run-1"));
        assertEquals("intent://run-1", projection.references().get("intentRunRef"));
    }

    @Test
    void canaryProposalIsMarkedDangerousWithHighRisk() {
        BusinessSafetyValveAdapter adapter = new BusinessSafetyValveAdapter();
        TeamBlueprintRecord team = new TeamBlueprintRecord()
            .setWorkspaceId("customer-service")
            .setTeamId("after-sales")
            .setName("售后团队");
        TeamBlueprintVersion version = new TeamBlueprintVersion()
            .setVersion(2)
            .setStatus("DRAFT")
            .setPromptAssetRefs(List.of("prompt://base"));

        var projection = adapter.toCanaryProposal(team, version, "pm");

        assertEquals(BusinessSafetyValveAdapter.ValveType.CANARY, projection.valveType());
        assertTrue(projection.approvalRequest().isDangerous());
        assertTrue(projection.approvalRequest().getDetails().contains("TeamBlueprintCompiler"));
        assertTrue(projection.delegatedTaskEnvelope().runId().contains("canary:after-sales@v2"));
        assertEquals("pm", projection.metadata().get("requestedBy"));
    }

    @Test
    void rollbackProposalRequiresDistinctVersions() {
        BusinessSafetyValveAdapter adapter = new BusinessSafetyValveAdapter();
        TeamBlueprintRecord team = new TeamBlueprintRecord()
            .setWorkspaceId("customer-service")
            .setTeamId("after-sales");

        assertThrows(IllegalArgumentException.class, () -> adapter.toRollbackProposal(team, 2, 2, "ops"));
        assertThrows(IllegalArgumentException.class, () -> adapter.toRollbackProposal(team, 0, 2, "ops"));
        assertThrows(IllegalArgumentException.class, () -> adapter.toRollbackProposal(team, 2, 0, "ops"));

        var projection = adapter.toRollbackProposal(team, 3, 1, "ops");
        assertEquals(BusinessSafetyValveAdapter.ValveType.ROLLBACK, projection.valveType());
        assertTrue(projection.approvalRequest().isDangerous());
        assertEquals(3, projection.references().get("fromVersion"));
        assertEquals(1, projection.references().get("toVersion"));
    }

    @Test
    void toMapProducesSerializableProjection() {
        BusinessSafetyValveAdapter adapter = new BusinessSafetyValveAdapter();
        BusinessRunRecord run = new BusinessRunRecord()
            .setRunId("run-x")
            .setWorkspaceId("w")
            .setTeamId("t")
            .setTechnicalTraceRef("intent://run-x");

        var map = adapter.toReplayRequest(run, null).toMap();

        assertEquals("w", map.get("workspaceId"));
        assertEquals("REPLAY", map.get("valveType"));
        assertTrue(map.containsKey("approvalCard"));
        assertTrue(map.containsKey("delegatedTaskEnvelope"));
        assertTrue(map.containsKey("references"));
    }
}
