package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.collaboration.AgentRuntimeProfile;
import com.nousresearch.hermes.org.knowledge.KnowledgeEntry;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Tests for org-native tools: find_teammate, delegate_task,
 * query_org_knowledge, escalate_to_human.
 */
class OrgNativeToolsTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();


    private ToolRegistry registry;
    private TenantContext tenantContext;
    private TenantAwareToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = ToolRegistry.getInstance();
        OrgNativeTools.register(registry);

        // Register a test agent role so find_teammate has data
        var request = TenantProvisioningRequest.builder("test-tenant", "test-user").build();
        tenantContext = TenantContext.create("test-tenant", request);
        tenantContext.registerAgentRole("agent-1",
            new AgentRuntimeProfile("code-reviewer", "Review PRs", AgentRuntimeProfile.Level.SENIOR)
                .skills("java", "python", "security"));
        tenantContext.registerAgentRole("agent-2",
            new AgentRuntimeProfile("release-manager", "Manage releases", AgentRuntimeProfile.Level.LEAD)
                .skills("devops", "deployment", "ci-cd"));
        tenantContext.registerAgentRole("agent-3",
            new AgentRuntimeProfile("data-analyst", "Analyze data", AgentRuntimeProfile.Level.MID)
                .skills("sql", "python", "visualization"));

        // Seed some knowledge
        var kb = tenantContext.getOrgKnowledgeBase();
        kb.put(new KnowledgeEntry("kb-1", KnowledgeEntry.Type.SOP, KnowledgeEntry.Classification.INTERNAL,
            "Deploy to Production", "1. Run tests. 2. Create release tag. 3. Deploy via CI/CD.", "ops-team")
            .tag("deployment", "production"));
        kb.put(new KnowledgeEntry("kb-2", KnowledgeEntry.Type.FAQ, KnowledgeEntry.Classification.INTERNAL,
            "How to reset dev database", "Run ./scripts/dev-reset.sh from the repo root.", "devops")
            .tag("database", "dev"));

        dispatcher = new TenantAwareToolDispatcher(tenantContext, registry);
    }

    @Test
    void findTeammate_bySkill() {
        String result = dispatcher.dispatch("find_teammate", Map.of("skill", "python"));
        assertTrue(result.contains("code-reviewer") || result.contains("data-analyst"));
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void findTeammate_byRole() {
        String result = dispatcher.dispatch("find_teammate", Map.of("role", "release"));
        assertTrue(result.contains("release-manager"));
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void findTeammate_byLevel() {
        String result = dispatcher.dispatch("find_teammate", Map.of("level", "LEAD"));
        assertTrue(result.contains("release-manager"));
        assertFalse(result.contains("code-reviewer")); // SENIOR, not LEAD
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void findTeammate_noFilters() {
        String result = dispatcher.dispatch("find_teammate", Map.of());
        assertTrue(result.contains("\"count\":3") || result.contains("\"count\": 3"));
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void queryOrgKnowledge_findSOP() {
        String result = dispatcher.dispatch("query_org_knowledge",
            Map.of("query", "deploy", "max_results", 5));
        assertTrue(result.contains("Deploy to Production"));
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void queryOrgKnowledge_filterByType() {
        String result = dispatcher.dispatch("query_org_knowledge",
            Map.of("query", "database", "type", "FAQ", "max_results", 5));
        assertTrue(result.contains("How to reset dev database"));
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void queryOrgKnowledge_noResults() {
        String result = dispatcher.dispatch("query_org_knowledge",
            Map.of("query", "nonexistent_topic_xyz", "max_results", 5));
        assertTrue(result.contains("\"count\":0") || result.contains("\"count\": 0"));
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void queryOrgKnowledge_missingQuery() {
        String result = dispatcher.dispatch("query_org_knowledge", Map.of());
        assertTrue(result.contains("error"));
    }

    @Test
    void delegateTask_targetNotRegistered() {
        String result = dispatcher.dispatch("delegate_task",
            Map.of("to", "nonexistent", "task", "do something"));
        assertTrue(result.contains("error") || result.contains("not available"));
    }

    @Test
    void delegateTask_missingTo() {
        String result = dispatcher.dispatch("delegate_task",
            Map.of("task", "do something"));
        assertTrue(result.contains("error"));
    }

    @Test
    void delegateTask_missingTask() {
        String result = dispatcher.dispatch("delegate_task",
            Map.of("to", "someone"));
        assertTrue(result.contains("error"));
    }

    @Test
    void escalateToHuman_basic() {
        String result = dispatcher.dispatch("escalate_to_human",
            Map.of("summary", "Need approval for deploy", "detail", "Deploying v2.0.1 to production"));
        assertTrue(result.contains("handoff_id"));
        assertTrue(result.contains("pending"));
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void escalateToHuman_withPriority() {
        String result = dispatcher.dispatch("escalate_to_human",
            Map.of("summary", "Critical issue", "detail", "DB down", "priority", "CRITICAL"));
        assertTrue(result.contains("handoff_id"));
        assertTrue(result.contains("pending"));
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void escalateToHuman_missingSummary() {
        String result = dispatcher.dispatch("escalate_to_human",
            Map.of("detail", "something"));
        assertTrue(result.contains("error"));
    }

    @Test
    void escalateToHuman_missingDetail() {
        String result = dispatcher.dispatch("escalate_to_human",
            Map.of("summary", "something"));
        assertTrue(result.contains("error"));
    }

    @Test
    void allToolsRegistered() {
        var tools = registry.getAllTools();
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("find_teammate")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("delegate_task")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("query_org_knowledge")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("escalate_to_human")));
    }

    @Test
    void toolsHaveOrgToolset() {
        var tools = registry.getAllTools();
        tools.stream()
            .filter(t -> t.getName().startsWith("find_") || t.getName().startsWith("delegate_") ||
                          t.getName().startsWith("query_org_") || t.getName().startsWith("escalate_"))
            .forEach(t -> assertEquals("organization", t.getToolset()));
    }
}
