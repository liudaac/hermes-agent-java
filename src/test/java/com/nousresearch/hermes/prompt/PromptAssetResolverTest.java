package com.nousresearch.hermes.prompt;

import com.nousresearch.hermes.org.knowledge.KnowledgeEntry;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PromptAssetResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesActiveAndPinnedPromptRefs() {
        Fixture fixture = fixture();
        fixture.promptAssetService.createPromptAsset("customer-service", "base", "Base Prompt", "purpose", "v1 content", List.of("base"), Map.of());
        fixture.promptAssetService.createDraftVersion("customer-service", "base", "v2 content", "change", Map.of());
        fixture.promptAssetService.activateVersion("customer-service", "base", 2);

        PromptContext context = fixture.resolver.resolve("customer-service", List.of("prompt://base", "prompt://base#v1"));

        assertEquals(2, context.getSegments().size());
        assertEquals("prompt://base#v2", context.getSegments().get(0).ref());
        assertEquals("v2 content", context.getSegments().get(0).content());
        assertEquals("prompt://base#v1", context.getSegments().get(1).ref());
        assertTrue(context.getWarnings().isEmpty());
        assertTrue(context.render().contains("Base Prompt #v2"));
    }

    @Test
    void reportsInvalidAndMissingPromptRefsAsWarnings() {
        Fixture fixture = fixture();
        PromptContext context = fixture.resolver.resolve("customer-service", List.of("bad://base", "prompt://missing"));

        assertEquals(0, context.getSegments().size());
        assertTrue(context.getWarnings().stream().anyMatch(w -> w.contains("prompt_ref_invalid")));
        assertTrue(context.getWarnings().stream().anyMatch(w -> w.contains("prompt_ref_missing")));
    }

    @Test
    void canIncludeFoundationMemorySkillsAndOrgKnowledge() throws Exception {
        Fixture fixture = fixture();
        fixture.promptAssetService.createPromptAsset("customer-service", "base", "Base Prompt", "purpose", "prompt content", List.of(), Map.of());
        var tenant = fixture.tenantManager.getTenant("customer-service");
        tenant.getMemoryManager().addMemory("售后政策需要先判断签收时间", Set.of("after-sales"));
        tenant.getSkillManager().createPrivateSkill("refund-check", "Check refund policy", "steps", List.of("refund"), Map.of());
        tenant.getOrgKnowledgeBase().put(new KnowledgeEntry("k1", KnowledgeEntry.Type.POLICY, KnowledgeEntry.Classification.INTERNAL, "Refund policy", "Refund within 7 days is allowed", "ops").tag("refund"));

        PromptContext context = fixture.resolver.resolve(
            "customer-service",
            List.of("prompt://base"),
            "refund policy",
            PromptAssetResolver.ResolveOptions.withFoundationContext()
        );

        assertTrue(context.getSegments().stream().anyMatch(s -> "business-prompt-asset".equals(s.source())));
        assertTrue(context.getSegments().stream().anyMatch(s -> "foundation-memory".equals(s.source())));
        assertTrue(context.getSegments().stream().anyMatch(s -> "foundation-skills".equals(s.source())));
        assertTrue(context.getSegments().stream().anyMatch(s -> "foundation-org-knowledge".equals(s.source())));
    }

    @Test
    void exposesExistenceBridgeForValidators() {
        Fixture fixture = fixture();
        fixture.promptAssetService.createPromptAsset("customer-service", "base", "Base Prompt", "purpose", "v1", List.of(), Map.of());

        assertTrue(fixture.resolver.exists("customer-service", "base", null));
        assertTrue(fixture.resolver.exists("customer-service", "base", 1));
        assertFalse(fixture.resolver.exists("customer-service", "base", 2));
    }

    private Fixture fixture() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        PromptAssetService promptAssetService = new PromptAssetService(tempDir.resolve("business/workspaces"), workspaceService);
        PromptAssetResolver resolver = new PromptAssetResolver(promptAssetService, workspaceService, tenantManager);
        return new Fixture(tenantManager, promptAssetService, resolver);
    }

    private record Fixture(TenantManager tenantManager, PromptAssetService promptAssetService, PromptAssetResolver resolver) {}
}
