package com.nousresearch.hermes.evolution;

import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvolutionProposalServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsListsAndLoadsProposal() {
        Fixture fixture = fixtureWithWorkspace(false);

        EvolutionProposalRecord proposal = fixture.evolutionProposalService.createProposal(
            "customer-service",
            "improve-after-sales",
            "after-sales-ticket",
            "after-sales-team",
            "insight-1",
            "优化售后判断口径",
            "失败运行集中在政策识别步骤",
            "补充先判断政策边界再生成建议的作业手册",
            "降低售后工单失败率",
            Map.of("failedRuns", 3),
            Map.of("source", "test")
        );

        assertEquals("customer-service", proposal.getWorkspaceId());
        assertEquals("improve-after-sales", proposal.getProposalId());
        assertEquals(EvolutionProposalService.DRAFT, proposal.getStatus());
        assertTrue(Files.exists(tempDir.resolve("business/workspaces/customer-service/evolution-proposals/improve-after-sales.json")));
        assertEquals(1, fixture.evolutionProposalService.listProposals("customer-service", null).size());
        assertEquals("优化售后判断口径", fixture.evolutionProposalService.requireProposal("customer-service", "improve-after-sales").getTitle());
    }

    @Test
    void supportsEvaluationApprovalAndRejectionFlow() {
        Fixture fixture = fixtureWithWorkspace(false);
        fixture.evolutionProposalService.createProposal("customer-service", "proposal-1", null, null, null, "提案", "发现", "变更", "收益", Map.of(), Map.of());

        assertEquals(EvolutionProposalService.EVALUATING, fixture.evolutionProposalService.startEvaluation("customer-service", "proposal-1").getStatus());
        assertEquals(EvolutionProposalService.NEEDS_APPROVAL, fixture.evolutionProposalService.requestApproval("customer-service", "proposal-1", "apv-1").getStatus());
        EvolutionProposalRecord approved = fixture.evolutionProposalService.approve("customer-service", "proposal-1", "owner", "looks good");
        assertEquals(EvolutionProposalService.APPROVED, approved.getStatus());
        assertEquals("owner", approved.getMetadata().get("approvedBy"));

        fixture.evolutionProposalService.createProposal("customer-service", "proposal-2", null, null, null, "提案", "发现", "变更", "收益", Map.of(), Map.of());
        EvolutionProposalRecord rejected = fixture.evolutionProposalService.reject("customer-service", "proposal-2", "owner", "not now");
        assertEquals(EvolutionProposalService.REJECTED, rejected.getStatus());
        assertEquals("not now", rejected.getMetadata().get("resolutionReason"));
    }

    @Test
    void appliesApprovedProposalAsTeamBlueprintDraft() {
        Fixture fixture = fixtureWithWorkspace(true);
        fixture.teamBlueprintService.createTeamBlueprint(
            "customer-service",
            "after-sales-team",
            "售后团队",
            "处理售后工单",
            "after-sales",
            null,
            List.of(new AgentBlueprintRecord().setAgentId("policy-expert").setDisplayName("政策专家")),
            List.of(),
            "v1 manual",
            Map.of()
        );
        fixture.evolutionProposalService.createProposal(
            "customer-service",
            "proposal-apply",
            null,
            "after-sales-team",
            null,
            "优化售后团队",
            "发现",
            "v2 manual from proposal",
            "收益",
            Map.of(),
            Map.of()
        );
        fixture.evolutionProposalService.approve("customer-service", "proposal-apply", "owner", "ok");

        EvolutionProposalRecord applied = fixture.evolutionProposalService.apply("customer-service", "proposal-apply", null);

        assertEquals(EvolutionProposalService.APPLIED, applied.getStatus());
        assertNotNull(applied.getAppliedAt());
        assertEquals("after-sales-team", applied.getTargetTeamId());
        assertEquals(2, applied.getTargetDraftVersion());
        assertEquals(2, fixture.teamBlueprintService.requireTeamBlueprint("customer-service", "after-sales-team").getVersions().size());
        assertEquals("DRAFT", fixture.teamBlueprintService.requireTeamBlueprint("customer-service", "after-sales-team").getVersions().get(1).getStatus());
    }

    @Test
    void rejectsDuplicateMissingWorkspaceAndInvalidTransitions() {
        Fixture fixture = fixtureWithWorkspace(false);
        fixture.evolutionProposalService.createProposal("customer-service", "proposal-1", null, null, null, "提案", "发现", "变更", "收益", Map.of(), Map.of());

        assertThrows(EvolutionProposalService.EvolutionProposalAlreadyExistsException.class,
            () -> fixture.evolutionProposalService.createProposal("customer-service", "proposal-1", null, null, null, "提案", "发现", "变更", "收益", Map.of(), Map.of()));
        assertThrows(WorkspaceService.WorkspaceNotFoundException.class,
            () -> fixture.evolutionProposalService.createProposal("missing", "proposal-1", null, null, null, "提案", "发现", "变更", "收益", Map.of(), Map.of()));
        assertThrows(EvolutionProposalService.InvalidEvolutionProposalTransitionException.class,
            () -> fixture.evolutionProposalService.apply("customer-service", "proposal-1", null));
    }

    private Fixture fixtureWithWorkspace(boolean withTeamBlueprintService) {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        TeamBlueprintService teamBlueprintService = withTeamBlueprintService
            ? new TeamBlueprintService(tempDir.resolve("business/workspaces"), workspaceService)
            : null;
        EvolutionProposalService evolutionProposalService = new EvolutionProposalService(
            tempDir.resolve("business/workspaces"),
            workspaceService,
            teamBlueprintService
        );
        return new Fixture(workspaceService, teamBlueprintService, evolutionProposalService);
    }

    private record Fixture(WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, EvolutionProposalService evolutionProposalService) {}
}
