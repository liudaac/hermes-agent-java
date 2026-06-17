package com.nousresearch.hermes.business.insight;

import com.nousresearch.hermes.org.eval.AgentEvaluation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessEvalRunProjectionAdapterTest {

    @Test
    void projectsEvalResultIntoReadOnlyEvalRunRecord() {
        AgentEvaluation.EvalResult result = new AgentEvaluation.EvalResult.Builder()
            .agentId("classifier")
            .agentVersion("v1")
            .task("classify refund")
            .score(AgentEvaluation.Dimension.ACCURACY, 0.6)
            .score(AgentEvaluation.Dimension.SAFETY, 0.9)
            .duration(Duration.ofMillis(300))
            .toolCalls(2)
            .tokens(120)
            .cost(0.02)
            .passed(true)
            .notes("ok")
            .build();

        var projection = new BusinessEvalRunProjectionAdapter().fromEvalResult("customer-service", result);

        assertEquals("customer-service", projection.workspaceId());
        assertEquals("classifier", projection.agentId());
        assertEquals("v1", projection.agentVersion());
        assertEquals("classify refund", projection.task());
        assertTrue(projection.passed());
        assertEquals(300L, projection.durationMs());
        assertEquals(2, projection.toolCalls());
        assertEquals(120L, projection.tokens());
        assertEquals(0.02, projection.estimatedCost(), 1e-9);
        assertEquals(0.6, projection.scores().get("ACCURACY"));
        assertEquals(0.9, projection.scores().get("SAFETY"));
        assertEquals("foundation:agent-evaluation", projection.metadata().get("source"));
        assertNotNull(projection.metadata().get("evalId"));
        assertTrue(projection.evalRunId().startsWith("eval-run-"));
        Map<?, ?> meta = (Map<?, ?>) projection.toMap().get("metadata");
        assertEquals("foundation:agent-evaluation", meta.get("source"));
    }

    @Test
    void fromEvalResultsHandlesEmptyAndMixedInputs() {
        var adapter = new BusinessEvalRunProjectionAdapter();
        assertTrue(adapter.fromEvalResults("customer-service", null).isEmpty());
        assertTrue(adapter.fromEvalResults("customer-service", List.of()).isEmpty());

        AgentEvaluation.EvalResult result = new AgentEvaluation.EvalResult.Builder()
            .agentId("classifier")
            .agentVersion("v1")
            .task("classify")
            .score(AgentEvaluation.Dimension.ACCURACY, 0.4)
            .duration(Duration.ofMillis(100))
            .passed(false)
            .build();
        var projections = adapter.fromEvalResults("customer-service", Arrays.asList(result, null));
        assertEquals(1, projections.size());
        assertFalse(projections.get(0).passed());
    }
}
