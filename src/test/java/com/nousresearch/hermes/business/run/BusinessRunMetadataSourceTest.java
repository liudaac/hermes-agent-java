package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessRunMetadataSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void manualRunIsTaggedManualSource() {
        BusinessRunService service = newService();
        BusinessRunRecord run = service.createRun("customer-service", "after-sales", "售后", "after-sales-ticket",
            "Manual smoke run", "manual input", "ok", "manual", "noop", "low", "review",
            BusinessRunService.COMPLETED, null, List.of(),
            Map.of(), null);

        assertEquals(BusinessRunService.SOURCE_MANUAL, run.getMetadata().get("source"));
    }

    @Test
    void runWithIntentTraceRefIsTaggedFoundationIntentRun() {
        BusinessRunService service = newService();
        BusinessRunRecord run = service.createRun("customer-service", "after-sales", "售后", "after-sales-ticket",
            "title", "input", "summary", "reason", "system", "low", "next",
            BusinessRunService.COMPLETED, "intent://run-1", List.of(),
            Map.of(), null);

        assertEquals(BusinessRunService.SOURCE_FOUNDATION_INTENT_RUN, run.getMetadata().get("source"));
    }

    @Test
    void runWithTraceRefIsTaggedFoundationAgentTrace() {
        BusinessRunService service = newService();
        BusinessRunRecord run = service.createRun("customer-service", "after-sales", "售后", "after-sales-ticket",
            "title", "input", "summary", "reason", "system", "low", "next",
            BusinessRunService.COMPLETED, "trace://abc", List.of(),
            Map.of(), null);

        assertEquals(BusinessRunService.SOURCE_FOUNDATION_AGENT_TRACE, run.getMetadata().get("source"));
    }

    @Test
    void unknownExistingSourceIsReplacedAndPreservedAsOriginal() {
        BusinessRunService service = newService();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "ad-hoc");
        BusinessRunRecord run = service.createRun("customer-service", "after-sales", "售后", "after-sales-ticket",
            "title", "input", "summary", "reason", "system", "low", "next",
            BusinessRunService.COMPLETED, null, List.of(),
            Map.of(), metadata);

        assertEquals(BusinessRunService.SOURCE_MANUAL, run.getMetadata().get("source"));
        assertEquals("ad-hoc", run.getMetadata().get("originalSource"));
    }

    @Test
    void knownExistingSourceIsKept() {
        BusinessRunService service = newService();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", BusinessRunService.SOURCE_DEMO);
        BusinessRunRecord run = service.createRun("customer-service", "after-sales", "售后", "after-sales-ticket",
            "title", "input", "summary", "reason", "system", "low", "next",
            BusinessRunService.COMPLETED, null, List.of(),
            Map.of(), metadata);

        assertEquals(BusinessRunService.SOURCE_DEMO, run.getMetadata().get("source"));
        assertNull(run.getMetadata().get("originalSource"));
    }

    private BusinessRunService newService() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        TeamBlueprintService teamBlueprintService = new TeamBlueprintService(tempDir.resolve("business/workspaces"), workspaceService);
        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("business/workspaces"), workspaceService, teamBlueprintService);
        return new BusinessRunService(tempDir.resolve("business/workspaces"), workspaceService, teamBlueprintService, scenarioService);
    }
}
