package com.nousresearch.hermes.workspace;

import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createWorkspaceAlsoCreatesTenantAndPersistsRecord() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService service = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);

        WorkspaceRecord record = service.createWorkspace(
            "customer-service",
            "客服业务空间",
            "售后工单处理",
            "ops",
            Map.of("department", "customer-service")
        );

        assertEquals("customer-service", record.getWorkspaceId());
        assertEquals("customer-service", record.getTenantId());
        assertTrue(tenantManager.exists("customer-service"));
        assertTrue(Files.exists(tempDir.resolve("business/workspaces/customer-service/workspace.json")));
        assertEquals(1, service.listWorkspaces().size());
        assertEquals("客服业务空间", service.requireWorkspace("customer-service").getName());
    }

    @Test
    void duplicateWorkspaceIsRejected() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService service = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);

        service.createWorkspace("sales-space", "销售空间", null, "ops", Map.of());

        assertThrows(WorkspaceService.WorkspaceAlreadyExistsException.class,
            () -> service.createWorkspace("sales-space", "销售空间", null, "ops", Map.of()));
    }
}
