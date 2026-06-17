package com.nousresearch.hermes.business.insight;

import com.nousresearch.hermes.org.eval.AgentEvaluation;
import com.nousresearch.hermes.org.observe.AgentTrace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessInsightProjectionAdapterTest {

    @Test
    void projectsTraceFailuresAndHandoffsIntoBusinessInsights() {
        AgentTrace failed = new AgentTrace("trace-1", "classifier", "session-1", "refund ticket")
            .step(AgentTrace.Step.toolCall("order.query", "{}", List.of("manual"), 0.8, 120, 0.02))
            .step(AgentTrace.Step.error("Order service denied permission"))
            .end(AgentTrace.Status.FAILED);
        AgentTrace handoff = new AgentTrace("trace-2", "policy", "session-2", "refund policy")
            .step(AgentTrace.Step.handoff("Refund amount exceeds policy threshold"))
            .end(AgentTrace.Status.PARTIAL);

        BusinessInsightSummary summary = new BusinessInsightProjectionAdapter()
            .fromFoundationSignals("customer-service", List.of(failed, handoff), List.of(), Map.of());

        assertEquals("customer-service", summary.getWorkspaceId());
        assertEquals(2, summary.getRunCount());
        assertEquals(2, summary.getFailedRunCount());
        assertEquals(1, summary.getNeedsApprovalRunCount());
        assertTrue(summary.getInsights().stream().anyMatch(insight -> "foundation-trace-failures".equals(insight.getInsightId())));
        assertTrue(summary.getInsights().stream().anyMatch(insight -> "foundation-human-handoffs".equals(insight.getInsightId())));
        assertTrue(summary.getNextActions().stream().anyMatch(action -> "review-foundation-traces".equals(action.get("id"))));
    }

    @Test
    void projectsFailedEvalResultsIntoBusinessInsights() {
        AgentEvaluation.EvalResult eval = new AgentEvaluation.EvalResult.Builder()
            .agentId("classifier")
            .agentVersion("v1")
            .task("classify refund")
            .score(AgentEvaluation.Dimension.ACCURACY, 0.4)
            .score(AgentEvaluation.Dimension.SAFETY, 0.9)
            .duration(Duration.ofMillis(250))
            .toolCalls(2)
            .tokens(100)
            .cost(0.01)
            .passed(false)
            .notes("Wrong category")
            .build();

        var insights = new BusinessInsightProjectionAdapter().fromEvalResults("customer-service", List.of(eval));

        assertEquals(1, insights.size());
        assertEquals("foundation-eval-regression", insights.get(0).getInsightId());
        assertEquals("HIGH", insights.get(0).getSeverity());
        assertEquals("foundation:agent-evaluation", insights.get(0).getMetrics().get("source"));
    }

    @Test
    void projectsEvolutionSummaryIntoBusinessInsights() {
        Map<String, Object> evolution = Map.of(
            "total_failures", 5,
            "resolved", 1,
            "pending_suggestions", 2,
            "root_causes", Map.of("INSUFFICIENT_CONTEXT", 4)
        );

        var insights = new BusinessInsightProjectionAdapter().fromEvolutionSummary("customer-service", evolution);

        assertTrue(insights.stream().anyMatch(insight -> "foundation-evolution-backlog".equals(insight.getInsightId())));
        assertTrue(insights.stream().anyMatch(insight -> "foundation-evolution-suggestions".equals(insight.getInsightId())));
        assertTrue(insights.stream().allMatch(insight -> "foundation:self-evolution".equals(insight.getMetrics().get("source"))));
    }

    @Test
    void emitsHealthyBaselineWhenNoFoundationProblemsExist() {
        AgentTrace ok = new AgentTrace("trace-ok", "agent", "session", "task")
            .step(AgentTrace.Step.decision("done", 0.9, List.of()))
            .end(AgentTrace.Status.SUCCESS);

        BusinessInsightSummary summary = new BusinessInsightProjectionAdapter()
            .fromFoundationSignals("customer-service", List.of(ok), List.of(), Map.of("total_failures", 0, "resolved", 0));

        assertTrue(summary.getInsights().stream().anyMatch(insight -> "foundation-trace-cost".equals(insight.getInsightId())
            || "foundation-insight-healthy".equals(insight.getInsightId())));
    }
}
