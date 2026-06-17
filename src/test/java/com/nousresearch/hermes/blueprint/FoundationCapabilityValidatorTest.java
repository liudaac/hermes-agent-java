package com.nousresearch.hermes.blueprint;

import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.approval.ToolRisk;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FoundationCapabilityValidatorTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsUnknownToolAsFoundationError() {
        Fixture fixture = newFixture();
        TeamBlueprintRecord team = team(List.of(agent("classifier", "order.query")), List.of("prompt://base"));

        FoundationCapabilityValidationReport report = fixture.validator.validateTeamBlueprint("customer-service", team);

        assertFalse(report.isValid());
        assertFinding(report, "requested_tool_unavailable");
    }

    @Test
    void validatesRegisteredToolAndWarnsWhenApprovalRequired() {
        Fixture fixture = newFixture();
        fixture.registry.register(new ToolEntry.Builder()
            .name("order.query")
            .toolset("business")
            .schema(Map.of("description", "Query order"))
            .handler(args -> "{}")
            .risk(ToolRisk.HIGH)
            .requiresApproval(true)
            .approvalType(ApprovalSystem.ApprovalType.CODE_EXECUTION)
            .build());
        TeamBlueprintRecord team = team(List.of(agent("classifier", "order.query")), List.of("prompt://base"));

        FoundationCapabilityValidationReport report = fixture.validator.validateTeamBlueprint("customer-service", team);

        assertTrue(report.isValid());
        assertFinding(report, "tool_requires_approval");
        assertTrue(report.hasWarnings());
    }

    @Test
    void reportsTenantPolicyDenial() {
        TenantSecurityPolicy policy = TenantSecurityPolicy.defaults();
        policy.setDeniedTools(Set.of("order.query"));
        Fixture fixture = newFixture(policy);
        fixture.registry.register(new ToolEntry.Builder()
            .name("order.query")
            .toolset("business")
            .schema(Map.of("description", "Query order"))
            .handler(args -> "{}")
            .build());
        TeamBlueprintRecord team = team(List.of(agent("classifier", "order.query")), List.of("prompt://base"));

        FoundationCapabilityValidationReport report = fixture.validator.validateTeamBlueprint("customer-service", team);

        assertFalse(report.isValid());
        assertFinding(report, "requested_tool_denied_by_tenant_policy");
    }

    @Test
    void reportsInvalidPromptRefAndDuplicateAgentId() {
        Fixture fixture = newFixture();
        fixture.registry.register(new ToolEntry.Builder()
            .name("order.query")
            .toolset("business")
            .schema(Map.of("description", "Query order"))
            .handler(args -> "{}")
            .build());
        TeamBlueprintRecord team = team(
            List.of(agent("classifier", "order.query"), agent("classifier", "order.query")),
            List.of("prompt://base#bad")
        );

        FoundationCapabilityValidationReport report = fixture.validator.validateTeamBlueprint("customer-service", team);

        assertFalse(report.isValid());
        assertFinding(report, "prompt_ref_invalid");
        assertFinding(report, "agent_id_duplicate");
    }

    private Fixture newFixture() {
        return newFixture(TenantSecurityPolicy.defaults());
    }

    private Fixture newFixture(TenantSecurityPolicy policy) {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        tenantManager.getTenant("customer-service").getSecurityPolicy().setAllowedTools(policy.getAllowedTools());
        tenantManager.getTenant("customer-service").getSecurityPolicy().setDeniedTools(policy.getDeniedTools());
        ToolRegistry registry = isolatedRegistry();
        FoundationCapabilityValidator validator = new FoundationCapabilityValidator(workspaceService, tenantManager, registry);
        return new Fixture(validator, registry);
    }

    private ToolRegistry isolatedRegistry() {
        try {
            var constructor = ToolRegistry.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TeamBlueprintRecord team(List<AgentBlueprintRecord> agents, List<String> promptRefs) {
        TeamBlueprintVersion version = new TeamBlueprintVersion()
            .setVersion(1)
            .setStatus("ACTIVE")
            .setAgents(agents)
            .setPromptAssetRefs(promptRefs)
            .setCreatedAt(Instant.now())
            .setActivatedAt(Instant.now());
        return new TeamBlueprintRecord()
            .setWorkspaceId("customer-service")
            .setTeamId("after-sales")
            .setName("售后团队")
            .setActiveVersion(1)
            .setVersions(List.of(version));
    }

    private AgentBlueprintRecord agent(String id, String tool) {
        return new AgentBlueprintRecord()
            .setAgentId(id)
            .setDisplayName(id)
            .setResponsibility("Handle " + id)
            .setAllowedTools(List.of(tool));
    }

    private void assertFinding(FoundationCapabilityValidationReport report, String code) {
        assertTrue(report.getFindings().stream().anyMatch(finding -> code.equals(finding.code())),
            () -> "Expected finding " + code + " in " + report.toMap());
    }

    private record Fixture(FoundationCapabilityValidator validator, ToolRegistry registry) {}
}
