package com.nousresearch.hermes.blueprint;

import com.nousresearch.hermes.collaboration.AgentRole;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeamBlueprintCompilerTest {
    @TempDir
    Path tempDir;

    @Test
    void compilesActiveVersionIntoTeamAndAgentRoles() {
        Fixture fixture = newFixture();
        fixture.registry.register(new ToolEntry.Builder()
            .name("order.query")
            .toolset("business")
            .schema(Map.of("description", "Query order"))
            .handler(args -> "{}")
            .build());
        TeamBlueprintRecord blueprint = team(List.of(agent("classifier", "工单分类员", "order.query")));

        TeamBlueprintCompileResult result = fixture.compiler.compileActiveVersion("customer-service", blueprint);

        assertTrue(result.isApplied());
        assertEquals(List.of("classifier"), result.getRegisteredAgents());
        assertEquals("classifier", result.getLeadAgentId());

        TenantContext tenant = fixture.tenantManager.getTenant("customer-service");
        var team = tenant.getTeamManager().getTeam("after-sales");
        assertNotNull(team);
        assertTrue(team.hasMember("classifier"));
        assertEquals("classifier", team.getLead());
        assertEquals(1, team.getState("business_blueprint_version"));

        AgentRole role = tenant.getAgentRole("classifier");
        assertNotNull(role);
        assertEquals("工单分类员", role.getRoleName());
        assertTrue(role.getAllowedTools().contains("order.query"));
        assertTrue(role.getResponsibilities().contains("处理工单分类员"));
        assertEquals(true, role.getMetrics().get("business_blueprint_compiled"));
    }

    @Test
    void refusesToCompileWhenFoundationValidationHasErrors() {
        Fixture fixture = newFixture();
        TeamBlueprintRecord blueprint = team(List.of(agent("classifier", "工单分类员", "missing.tool")));

        TeamBlueprintCompileResult result = fixture.compiler.compileActiveVersion("customer-service", blueprint);

        assertFalse(result.isApplied());
        assertTrue(result.getValidationReport().hasErrors());
        assertNull(fixture.tenantManager.getTenant("customer-service").getTeamManager().getTeam("after-sales"));
        assertNull(fixture.tenantManager.getTenant("customer-service").getAgentRole("classifier"));
    }

    private Fixture newFixture() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        ToolRegistry registry = isolatedRegistry();
        FoundationCapabilityValidator validator = new FoundationCapabilityValidator(workspaceService, tenantManager, registry);
        TeamBlueprintCompiler compiler = new TeamBlueprintCompiler(workspaceService, tenantManager, validator);
        return new Fixture(tenantManager, registry, compiler);
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

    private TeamBlueprintRecord team(List<AgentBlueprintRecord> agents) {
        TeamBlueprintVersion version = new TeamBlueprintVersion()
            .setVersion(1)
            .setStatus("ACTIVE")
            .setAgents(agents)
            .setPromptAssetRefs(List.of("prompt://after-sales-base"))
            .setOperatingManual("先分类，再判断政策，最后生成回复。")
            .setCreatedAt(Instant.now())
            .setActivatedAt(Instant.now());
        return new TeamBlueprintRecord()
            .setWorkspaceId("customer-service")
            .setTeamId("after-sales")
            .setName("售后工单团队")
            .setDescription("处理售后工单")
            .setScenarioId("after-sales-ticket")
            .setActiveVersion(1)
            .setVersions(List.of(version));
    }

    private AgentBlueprintRecord agent(String id, String name, String tool) {
        return new AgentBlueprintRecord()
            .setAgentId(id)
            .setDisplayName(name)
            .setResponsibility("处理" + name)
            .setKnowledgeRefs(List.of("knowledge://policy"))
            .setAllowedTools(List.of(tool))
            .setApprovalRules(List.of("退款超过 1000 元必须人工审批"));
    }

    private record Fixture(TenantManager tenantManager, ToolRegistry registry, TeamBlueprintCompiler compiler) {}
}
