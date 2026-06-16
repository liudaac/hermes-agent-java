package com.nousresearch.hermes.blueprint;

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

class TeamBlueprintServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsV1ActiveThenDraftAndActivatesV2() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        TeamBlueprintService service = new TeamBlueprintService(tempDir.resolve("business/workspaces"), workspaceService);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());

        TeamBlueprintRecord team = service.createTeamBlueprint(
            "customer-service",
            "after-sales",
            "售后工单团队",
            "处理售后工单",
            "售后工单处理",
            "after-sales-ticket",
            List.of(agent("classifier", "工单分类员")),
            List.of("prompt://after-sales/base"),
            "先分类，再判断政策，最后生成回复。",
            Map.of("portal", "business")
        );

        assertEquals(1, team.getActiveVersion());
        assertEquals(1, team.getVersions().size());
        assertEquals("ACTIVE", team.getVersions().getFirst().getStatus());
        assertEquals(List.of("prompt://after-sales/base"), team.getVersions().getFirst().getPromptAssetRefs());
        assertTrue(Files.exists(tempDir.resolve("business/workspaces/customer-service/team-blueprints/after-sales.json")));

        TeamBlueprintVersion draft = service.createDraftVersion(
            "customer-service",
            "after-sales",
            "新增特殊类目政策判断",
            List.of(agent("classifier", "工单分类员"), agent("special-policy", "特殊类目政策专家")),
            List.of("prompt://after-sales/base", "prompt://after-sales/special-policy"),
            "遇到生鲜和定制商品必须先走特殊类目判断。",
            Map.of("reason", "人工纠正率上升")
        );

        assertEquals(2, draft.getVersion());
        assertEquals("DRAFT", draft.getStatus());

        TeamBlueprintRecord activated = service.activateVersion("customer-service", "after-sales", 2);
        assertEquals(2, activated.getActiveVersion());
        assertEquals("INACTIVE", activated.getVersions().get(0).getStatus());
        assertEquals("ACTIVE", activated.getVersions().get(1).getStatus());
        assertEquals(2, service.requireTeamBlueprint("customer-service", "after-sales").getActiveVersion());
    }

    @Test
    void rejectsTeamBlueprintForMissingWorkspace() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        TeamBlueprintService service = new TeamBlueprintService(tempDir.resolve("business/workspaces"), workspaceService);

        assertThrows(WorkspaceService.WorkspaceNotFoundException.class,
            () -> service.createTeamBlueprint("missing", "team-a", "Team A", null, null, null, List.of(), List.of(), null, Map.of()));
    }

    private AgentBlueprintRecord agent(String id, String name) {
        return new AgentBlueprintRecord()
            .setAgentId(id)
            .setDisplayName(name)
            .setResponsibility("处理" + name)
            .setKnowledgeRefs(List.of("knowledge://policy"))
            .setAllowedTools(List.of("order.query"))
            .setApprovalRules(List.of("退款超过 1000 元必须人工审批"));
    }
}
