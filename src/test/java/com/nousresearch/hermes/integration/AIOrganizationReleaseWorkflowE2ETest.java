package com.nousresearch.hermes.integration;

import com.nousresearch.hermes.collaboration.AgentMessage;
import com.nousresearch.hermes.collaboration.AgentRole;
import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.org.evolution.SelfEvolutionEngine;
import com.nousresearch.hermes.org.knowledge.KnowledgeEntry;
import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.impl.OrgNativeTools;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end release workflow demo for the AI-native organization stack.
 *
 * <p>This test deliberately exercises all five "knives" together instead of
 * validating them in isolation:</p>
 *
 * <ol>
 *   <li>Org-native tools: knowledge query, team state, orchestration, traces</li>
 *   <li>Self-evolution: lessons learned are captured and injected into prompt context</li>
 *   <li>Team context: release team shares decisions and activity</li>
 *   <li>Intent self-organization: release intent is planned and executed via teammates</li>
 *   <li>Org observability: release trace is recorded and queryable</li>
 * </ol>
 */
class AIOrganizationReleaseWorkflowE2ETest {

    @Test
    void releaseWorkflowExercisesFullOrgNativeLoop() throws Exception {
        String tenantId = "release-e2e-" + System.nanoTime();
        var request = TenantProvisioningRequest.builder(tenantId, "e2e-user").build();
        TenantContext tenant = TenantContext.create(tenantId, request);
        tenant.initCollaboration();

        ToolRegistry registry = ToolRegistry.getInstance();
        OrgNativeTools.register(registry);
        TenantAwareToolDispatcher dispatcher = new TenantAwareToolDispatcher(tenant, registry);

        // ===== 0. Organization setup: roles, team, knowledge, fake teammates =====
        tenant.registerAgentRole("agent-reviewer",
            new AgentRole("code-reviewer", "Reviews release code", AgentRole.Level.SENIOR)
                .skills("java", "code", "review", "tests"));
        tenant.registerAgentRole("agent-qa",
            new AgentRole("qa-engineer", "Runs release tests", AgentRole.Level.MID)
                .skills("qa", "test", "tests", "validation"));
        tenant.registerAgentRole("agent-release",
            new AgentRole("release-manager", "Deploys releases", AgentRole.Level.LEAD)
                .skills("release", "deploy", "deployment", "production", "ci-cd"));

        var team = tenant.getTeamManager().createTeam(
            "release-team", "Release Team", "Ship safe production releases", "e2e-user");
        team.addMember("agent-reviewer");
        team.addMember("agent-qa");
        team.addMember("agent-release");
        team.setLead("agent-release");

        tenant.getOrgKnowledgeBase().put(new KnowledgeEntry(
            "release-sop", KnowledgeEntry.Type.SOP, KnowledgeEntry.Classification.INTERNAL,
            "Production Release SOP",
            "1. Review code. 2. Run tests. 3. Deploy production. 4. Notify human if risk is high.",
            "release-office"
        ).tag("release", "production", "sop"));

        AtomicInteger delegated = new AtomicInteger();
        var bus = tenant.getTenantBus();
        bus.register("agent-reviewer", msg -> reply(bus, msg, "code review passed"));
        bus.register("agent-qa", msg -> reply(bus, msg, "tests passed"));
        bus.register("agent-release", msg -> {
            delegated.incrementAndGet();
            reply(bus, msg, "deployment completed");
        });

        // ===== 1. Org-native knowledge query =====
        String knowledge = dispatcher.dispatch("query_org_knowledge",
            Map.of("query", "release", "type", "SOP", "max_results", 5));
        assertTrue(knowledge.contains("Production Release SOP"), knowledge);

        // ===== 2. Team shared decision =====
        String post = dispatcher.dispatch("team_post", Map.of(
            "key", "decision:release-window",
            "content", "Release after tests pass; escalate if deployment is risky",
            "tag", "decision"));
        assertTrue(post.contains("posted"), post);

        String teamRead = dispatcher.dispatch("team_read", Map.of("key_pattern", "decision:", "limit", 10));
        assertTrue(teamRead.contains("decision:release-window"), teamRead);

        // ===== 3. Intent-driven self-organization plan =====
        String intent = "review java code, run tests, then deploy production";
        String plan = dispatcher.dispatch("orchestrate_intent", Map.of("intent", intent, "mode", "plan"));
        assertTrue(plan.contains("agent-reviewer"), plan);
        assertTrue(plan.contains("agent-qa") || plan.contains("agent-release"), plan);
        assertTrue(plan.contains("agent-release"), plan);

        // ===== 4. Execute orchestration via TenantBus teammates =====
        String runStart = dispatcher.dispatch("orchestrate_intent", Map.of("intent", intent, "mode", "execute"));
        assertTrue(runStart.contains("run_id"), runStart);
        String runId = extractJsonString(runStart, "run_id");
        assertNotNull(runId, runStart);

        String status = "";
        for (int i = 0; i < 20; i++) {
            status = dispatcher.dispatch("intent_status", Map.of("run_id", runId));
            if (status.contains("COMPLETED") || status.contains("PARTIAL") || status.contains("FAILED")) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(status.contains("COMPLETED") || status.contains("PARTIAL"), status);
        assertTrue(delegated.get() >= 1, "Expected release-manager to receive at least one delegated task");

        // ===== 5. Self-evolution lesson captured =====
        SelfEvolutionEngine evolution = new SelfEvolutionEngine();
        var failure = new FailureCase.Builder("agent-release", "deploy production", "Missing release checklist")
            .rootCause(FailureCase.RootCause.INSUFFICIENT_CONTEXT)
            .severity(FailureCase.Severity.MEDIUM)
            .lesson("Always query release SOP before production deployment")
            .resolved(true)
            .build();
        evolution.recordFailure(failure);
        String evolutionPrompt = evolution.buildEvolutionPrompt("agent-release");
        assertTrue(evolutionPrompt.contains("release SOP") || evolutionPrompt.contains("Production"), evolutionPrompt);

        // ===== 6. Org-wide observability trace + replay via org_traces =====
        var trace = tenant.getObservability().startTrace("agent-release", "release-session", intent);
        trace.step(AgentTrace.Step.toolCall("query_org_knowledge", "release production", java.util.List.of(), 0.95, 100, 0.001));
        trace.step(AgentTrace.Step.toolResult("query_org_knowledge", "Production Release SOP", 12));
        trace.step(AgentTrace.Step.toolCall("orchestrate_intent", intent, java.util.List.of("manual workflow"), 0.9, 120, 0.002));
        trace.step(AgentTrace.Step.decision("Proceed with release after QA + review", 0.88, java.util.List.of("abort", "escalate")));
        trace.end(AgentTrace.Status.SUCCESS);
        tenant.getObservability().completeTrace(trace);

        String traces = dispatcher.dispatch("org_traces", Map.of("agent_id", "agent-release", "limit", 5));
        assertTrue(traces.contains("release-session"), traces);
        assertTrue(traces.contains("orchestrate_intent") || traces.contains("steps"), traces);

        String anomalies = dispatcher.dispatch("org_anomalies", Map.of("limit", 5));
        assertTrue(anomalies.contains("anomalies"), anomalies);

        // ===== Final: all layers are present in one workflow =====
        assertTrue(team.describeForPrompt().contains("Release Team"));
        assertTrue(tenant.getObservability().getAllRecentTraces(10).size() >= 1);
    }

    private static void reply(com.nousresearch.hermes.collaboration.TenantBus bus,
                              AgentMessage original,
                              String resultText) {
        AgentMessage reply = AgentMessage.builder(original.getReceiverId(), original.getSenderId(), AgentMessage.Type.RESPONSE)
            .action("done")
            .replyTo(original.getMessageId())
            .payload(Map.of("ok", true, "result", resultText))
            .build();
        reply.setResultText(resultText);
        bus.reply(original, reply);
    }

    /** Minimal JSON string extractor for ToolRegistry.toolResult output. */
    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\":";
        int pos = json.indexOf(needle);
        if (pos < 0) return null;
        int start = json.indexOf('"', pos + needle.length());
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }
}
