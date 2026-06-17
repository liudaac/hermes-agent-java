package com.nousresearch.hermes.business.approval;

import com.nousresearch.hermes.approval.ApprovalRequest;
import com.nousresearch.hermes.approval.ApprovalResult;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessApprovalAdapterTest {
    @TempDir
    Path tempDir;

    @Test
    void projectsApprovalRequestIntoBusinessCard() {
        ApprovalRequest request = new ApprovalRequest(
            ApprovalSystem.ApprovalType.FILE_DELETE,
            "delete /tmp/customer-data.csv",
            "User requested cleanup",
            true
        );

        BusinessApprovalRecord card = new BusinessApprovalAdapter().fromApprovalRequest("customer-service", "ops", request);

        assertTrue(card.getApprovalId().startsWith("business-apv-"));
        assertEquals("customer-service", card.getWorkspaceId());
        assertEquals("ops", card.getTeamId());
        assertEquals("File delete approval", card.getTitle());
        assertEquals("CRITICAL", card.getRiskLevel());
        assertEquals("PENDING", card.getStatus());
        assertEquals("foundation:approval-request", card.getMetadata().get("source"));
        assertEquals("FILE_DELETE", card.getEvidence().get("approvalType"));
        assertEquals(true, card.getEvidence().get("dangerous"));
    }

    @Test
    void mirrorsApprovalResultOntoCardProjection() {
        ApprovalRequest request = new ApprovalRequest(
            ApprovalSystem.ApprovalType.CODE_EXECUTION,
            "python report.py",
            "Generate local report",
            false
        );
        BusinessApprovalAdapter adapter = new BusinessApprovalAdapter();
        BusinessApprovalRecord card = adapter.fromApprovalRequest("customer-service", "analytics", request);

        adapter.withResult(card, ApprovalResult.approvedForSession(), "ops-user");

        assertEquals("APPROVED", card.getStatus());
        assertEquals("ops-user", card.getResolvedBy());
        assertTrue(card.getResolutionReason().contains("session"));
        assertEquals(true, card.getMetadata().get("foundationSessionApproved"));
        assertNotNull(card.getResolvedAt());
    }

    @Test
    void mirrorsDeniedResultOntoCardProjection() {
        BusinessApprovalAdapter adapter = new BusinessApprovalAdapter();
        BusinessApprovalRecord card = adapter.fromApprovalRequest("customer-service", "ops",
            new ApprovalRequest(ApprovalSystem.ApprovalType.TERMINAL_COMMAND, "rm -rf /", "danger", true));

        adapter.withResult(card, ApprovalResult.denied("Too risky"), null);

        assertEquals("REJECTED", card.getStatus());
        assertEquals("Too risky", card.getResolutionReason());
        assertEquals("foundation-approval", card.getResolvedBy());
    }

    @Test
    void canPersistAndResolveThroughExistingBusinessApprovalService() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        BusinessApprovalService service = new BusinessApprovalService(tempDir.resolve("business/workspaces"), workspaceService);
        BusinessApprovalAdapter adapter = new BusinessApprovalAdapter();
        BusinessApprovalRecord projection = adapter.fromApprovalRequest("customer-service", "ops",
            new ApprovalRequest(ApprovalSystem.ApprovalType.FILE_WRITE, "write report.md", "Save report", false));

        BusinessApprovalRecord persisted = adapter.persistRequest(service, projection);
        assertTrue(persisted.getApprovalId().startsWith("apv-"));
        assertEquals("business-approval-service", persisted.getMetadata().getOrDefault("missing", "business-approval-service"));
        assertEquals(projection.getApprovalId(), persisted.getMetadata().get("projectionApprovalId"));

        BusinessApprovalRecord resolved = adapter.resolvePersisted(service, "customer-service", persisted.getApprovalId(), ApprovalResult.denied("Need more evidence"), "manager");
        assertEquals("REJECTED", resolved.getStatus());
        assertEquals("Need more evidence", resolved.getResolutionReason());
        assertEquals("manager", resolved.getResolvedBy());
    }
}
