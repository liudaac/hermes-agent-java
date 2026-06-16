package com.nousresearch.hermes.prompt;

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

class PromptAssetServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsListsAndLoadsPromptAsset() {
        Fixture fixture = fixtureWithWorkspace();

        PromptAssetRecord asset = fixture.promptAssetService.createPromptAsset(
            "customer-service",
            "after-sales-base",
            "售后基础提示词",
            "售后工单处理基础指令",
            "你是售后政策专家，请先判断政策再给出建议。",
            List.of("after-sales", "policy"),
            Map.of("source", "test")
        );

        assertEquals("customer-service", asset.getWorkspaceId());
        assertEquals("after-sales-base", asset.getAssetId());
        assertEquals(1, asset.getVersion());
        assertEquals("ACTIVE", asset.getStatus());
        assertEquals(List.of("after-sales", "policy"), asset.getTags());
        assertTrue(Files.exists(tempDir.resolve("business/workspaces/customer-service/prompt-assets/after-sales-base.json")));
        assertEquals(1, fixture.promptAssetService.listPromptAssets("customer-service").size());
        assertEquals("售后基础提示词", fixture.promptAssetService.requirePromptAsset("customer-service", "after-sales-base").getName());
    }


    @Test
    void createsDraftAndActivatesPromptAssetVersion() {
        Fixture fixture = fixtureWithWorkspace();
        fixture.promptAssetService.createPromptAsset("customer-service", "base", "Base", "purpose", "v1 content", List.of(), Map.of());

        PromptAssetVersion draft = fixture.promptAssetService.createDraftVersion("customer-service", "base", "v2 content", "Improve instructions", Map.of("source", "test"));
        assertEquals(2, draft.getVersion());
        assertEquals("DRAFT", draft.getStatus());

        PromptAssetRecord activated = fixture.promptAssetService.activateVersion("customer-service", "base", 2);
        assertEquals(2, activated.getActiveVersion());
        assertEquals("v2 content", activated.getContent());
        assertEquals("ACTIVE", fixture.promptAssetService.requireVersion("customer-service", "base", null).getStatus());
        assertEquals("v1 content", fixture.promptAssetService.requireVersion("customer-service", "base", 1).getContent());
        assertEquals("v2 content", fixture.promptAssetService.requireVersion("customer-service", "base", 2).getContent());
    }

    @Test
    void rejectsDuplicateAndMissingWorkspace() {
        Fixture fixture = fixtureWithWorkspace();
        fixture.promptAssetService.createPromptAsset("customer-service", "base", "Base", null, "content", List.of(), Map.of());

        assertThrows(PromptAssetService.PromptAssetAlreadyExistsException.class,
            () -> fixture.promptAssetService.createPromptAsset("customer-service", "base", "Base", null, "content", List.of(), Map.of()));
        assertThrows(WorkspaceService.WorkspaceNotFoundException.class,
            () -> fixture.promptAssetService.createPromptAsset("missing", "base", "Base", null, "content", List.of(), Map.of()));
    }

    private Fixture fixtureWithWorkspace() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        PromptAssetService promptAssetService = new PromptAssetService(tempDir.resolve("business/workspaces"), workspaceService);
        return new Fixture(workspaceService, promptAssetService);
    }

    private record Fixture(WorkspaceService workspaceService, PromptAssetService promptAssetService) {}
}
