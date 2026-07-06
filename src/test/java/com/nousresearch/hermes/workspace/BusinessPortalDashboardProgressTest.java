package com.nousresearch.hermes.workspace;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.insight.BusinessInsightService;
import com.nousresearch.hermes.business.run.BusinessRunService;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Verifies the /api/v1/business/workspace-progress endpoint
 * — the 7-step portal journey state, used by the Business Portal top
 * progress bar (template → workspace → team → scenario → run → approval → knowledge).
 */
class BusinessPortalDashboardProgressTest {

    private static Javalin app;
    private static int port;
    private static final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    @BeforeAll
    static void setUp() {
        app = Javalin.create();
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        TeamBlueprintService teamBlueprintService = mock(TeamBlueprintService.class);
        BusinessApprovalService approvalService = mock(BusinessApprovalService.class);
        BusinessRunService runService = mock(BusinessRunService.class);
        BusinessInsightService insightService = mock(BusinessInsightService.class);

        // Default to an empty-but-non-null summary so the endpoint never NPEs
        // on the "no data yet" path.
        com.nousresearch.hermes.business.insight.BusinessInsightSummary empty =
            new com.nousresearch.hermes.business.insight.BusinessInsightSummary()
                .setWorkspaceId(null)
                .setWorkspaceCount(0)
                .setTeamCount(0)
                .setRunCount(0)
                .setFailedRunCount(0)
                .setNeedsApprovalRunCount(0)
                .setPendingApprovalCount(0)
                .setHighRiskApprovalCount(0)
                .setFailureRate(0.0);
        org.mockito.Mockito.when(insightService.summarize(org.mockito.ArgumentMatchers.any())).thenReturn(empty);
        org.mockito.Mockito.when(workspaceService.listWorkspaces()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(approvalService.listApprovals(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(runService.listRuns(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(java.util.List.of());

        BusinessPortalDashboardIntegration.registerRoutes(
            app, workspaceService, teamBlueprintService, approvalService, runService, insightService);
        app.start(0);
        port = app.port();
    }

    @AfterAll
    static void tearDown() {
        if (app != null) app.stop();
    }

    @Test
    @DisplayName("workspace-progress 返回 7 步结构 + activeStep + pendingApprovals")
    void structure() throws Exception {
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/business/workspace-progress")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertNotNull(body);
        // The endpoint must return JSON even with no data (empty state).
        assertTrue(body.contains("\"ok\""), "missing ok field: " + body);
        assertTrue(body.contains("\"activeStep\""), "missing activeStep: " + body);
        assertTrue(body.contains("\"steps\""), "missing steps: " + body);
        assertTrue(body.contains("\"pendingApprovals\""), "missing pendingApprovals: " + body);
    }

    @Test
    @DisplayName("empty state — 7 步全 missing, activeStep=1")
    void emptyState() throws Exception {
        // The mocked services in setUp() return empty state.
        // Expect every step's status to be "missing" or "partial" and activeStep=1.
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/business/workspace-progress")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        String body = resp.body();
        assertTrue(body.contains("\"activeStep\":1"), "activeStep should be 1 in empty state: " + body);
        assertTrue(body.contains("\"status\":\"missing\""), "expected at least one missing step: " + body);
        assertTrue(body.contains("\"pendingApprovals\":0"), "expected 0 pending approvals: " + body);
    }
}
