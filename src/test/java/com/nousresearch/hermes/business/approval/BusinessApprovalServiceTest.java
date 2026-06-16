package com.nousresearch.hermes.business.approval;

import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessApprovalServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsAndApprovesBusinessApproval() {
        BusinessApprovalService service = serviceWithWorkspace();

        BusinessApprovalRecord approval = service.createApproval(
            "customer-service",
            "after-sales",
            "退款审批",
            "用户申请 1200 元退款，需要人工确认。",
            "金额超过自动退款阈值",
            "系统将生成同意退款的后续动作",
            "系统将转人工继续处理",
            "建议同意，但需要确认商品状态",
            "HIGH",
            Map.of("orderId", "O-1001", "amount", 1200),
            Map.of("source", "test")
        );

        assertEquals("PENDING", approval.getStatus());
        assertEquals("HIGH", approval.getRiskLevel());
        assertTrue(Files.exists(tempDir.resolve("business/workspaces/customer-service/approvals/" + approval.getApprovalId() + ".json")));
        assertEquals(1, service.listApprovals("customer-service", "PENDING").size());

        BusinessApprovalRecord approved = service.approve("customer-service", approval.getApprovalId(), "ops-user", "符合政策");
        assertEquals("APPROVED", approved.getStatus());
        assertEquals("ops-user", approved.getResolvedBy());
        assertEquals("符合政策", approved.getResolutionReason());
        assertNotNull(approved.getResolvedAt());
        assertEquals(0, service.listApprovals("customer-service", "PENDING").size());
        assertEquals(1, service.listApprovals("customer-service", "APPROVED").size());
    }

    @Test
    void supportsRejectAndRequestInfoAndPreventsDoubleResolution() {
        BusinessApprovalService service = serviceWithWorkspace();
        BusinessApprovalRecord rejectApproval = service.createApproval("customer-service", "team-a", "拒绝测试", "摘要", null, null, null, null, "MEDIUM", Map.of(), Map.of());
        BusinessApprovalRecord rejected = service.reject("customer-service", rejectApproval.getApprovalId(), "manager", "证据不足");
        assertEquals("REJECTED", rejected.getStatus());
        assertThrows(BusinessApprovalService.BusinessApprovalAlreadyResolvedException.class,
            () -> service.approve("customer-service", rejectApproval.getApprovalId(), "manager", "改为同意"));

        BusinessApprovalRecord infoApproval = service.createApproval("customer-service", "team-a", "补充信息测试", "摘要", null, null, null, null, "LOW", Map.of(), Map.of());
        BusinessApprovalRecord infoRequested = service.requestInfo("customer-service", infoApproval.getApprovalId(), "manager", "请补充商品照片");
        assertEquals("INFO_REQUESTED", infoRequested.getStatus());
        assertEquals("请补充商品照片", infoRequested.getRequestedInfo());
    }

    private BusinessApprovalService serviceWithWorkspace() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        return new BusinessApprovalService(tempDir.resolve("business/workspaces"), workspaceService);
    }
}
