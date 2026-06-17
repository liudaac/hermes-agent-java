package com.nousresearch.hermes.business.insight;

import com.nousresearch.hermes.org.eval.AgentEvaluation;
import com.nousresearch.hermes.org.observe.AgentTrace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Projects Hermes foundation observability/evaluation/evolution signals into
 * Business Portal insight records.
 *
 * <p>This adapter does not analyze file-backed BusinessRun records and does not
 * become an analytics source of truth. It translates existing foundation truth
 * into business-readable insight projections.</p>
 */
public class BusinessInsightProjectionAdapter {

    public BusinessInsightSummary fromFoundationSignals(String workspaceId,
                                                        List<AgentTrace> traces,
                                                        List<AgentEvaluation.EvalResult> evalResults,
                                                        Map<String, Object> evolutionSummary) {
        Instant now = Instant.now();
        List<AgentTrace> safeTraces = traces != null ? traces : List.of();
        List<AgentEvaluation.EvalResult> safeEvals = evalResults != null ? evalResults : List.of();
        Map<String, Object> safeEvolution = evolutionSummary != null ? evolutionSummary : Map.of();

        int failedTraceCount = (int) safeTraces.stream().filter(this::isFailedTrace).count();
        int handoffTraceCount = (int) safeTraces.stream().filter(this::hasHumanHandoff).count();
        int agentCount = (int) safeTraces.stream().map(AgentTrace::getAgentId).filter(Objects::nonNull).distinct().count();
        double failureRate = safeTraces.isEmpty() ? 0.0 : Math.round((failedTraceCount * 1000.0 / safeTraces.size())) / 10.0;

        BusinessInsightSummary summary = new BusinessInsightSummary()
            .setWorkspaceId(workspaceId != null ? workspaceId : "")
            .setWorkspaceCount(workspaceId == null || workspaceId.isBlank() ? 0 : 1)
            .setTeamCount(agentCount)
            .setRunCount(safeTraces.size())
            .setFailedRunCount(failedTraceCount)
            .setNeedsApprovalRunCount(handoffTraceCount)
            .setPendingApprovalCount(toInt(safeEvolution.get("pending_suggestions")))
            .setHighRiskApprovalCount(0)
            .setFailureRate(failureRate)
            .setGeneratedAt(now);

        List<BusinessInsightRecord> insights = new ArrayList<>();
        insights.addAll(traceInsights(workspaceId, safeTraces, now));
        insights.addAll(evalInsights(workspaceId, safeEvals, now));
        insights.addAll(evolutionInsights(workspaceId, safeEvolution, now));
        if (insights.isEmpty()) {
            insights.add(record("foundation-insight-healthy", workspaceId, "Foundation signals look healthy",
                "Recent foundation traces/evals/evolution summary do not show blocking issues.",
                "No severe trace failures, eval regressions or unresolved evolution backlog were projected.",
                "Continue collecting foundation traces and eval results before making optimization proposals.",
                "Keeps Business Portal recommendations grounded in runtime evidence.",
                "collect-foundation-signals", "INFO", baseMetrics(safeTraces, safeEvals, safeEvolution), now));
        }
        summary.setInsights(insights);
        summary.setNextActions(nextActions(insights));
        return summary;
    }

    public List<BusinessInsightRecord> fromTraces(String workspaceId, List<AgentTrace> traces) {
        return traceInsights(workspaceId, traces != null ? traces : List.of(), Instant.now());
    }

    public List<BusinessInsightRecord> fromEvalResults(String workspaceId, List<AgentEvaluation.EvalResult> evalResults) {
        return evalInsights(workspaceId, evalResults != null ? evalResults : List.of(), Instant.now());
    }

    public List<BusinessInsightRecord> fromEvolutionSummary(String workspaceId, Map<String, Object> evolutionSummary) {
        return evolutionInsights(workspaceId, evolutionSummary != null ? evolutionSummary : Map.of(), Instant.now());
    }

    private List<BusinessInsightRecord> traceInsights(String workspaceId, List<AgentTrace> traces, Instant now) {
        if (traces.isEmpty()) return List.of();
        List<BusinessInsightRecord> insights = new ArrayList<>();
        Map<String, Object> metrics = baseMetrics(traces, List.of(), Map.of());
        long failed = traces.stream().filter(this::isFailedTrace).count();
        long errored = traces.stream().filter(trace -> trace.getErrorCount() > 0).count();
        long handoffs = traces.stream().filter(this::hasHumanHandoff).count();
        double totalCost = traces.stream().mapToDouble(AgentTrace::getEstimatedCost).sum();
        long totalTokens = traces.stream().mapToLong(AgentTrace::getTotalTokens).sum();

        if (failed > 0 || errored > 0) {
            insights.add(record("foundation-trace-failures", workspaceId, "Foundation traces contain failures",
                "Recent AgentTrace data shows " + failed + " failed trace(s) and " + errored + " trace(s) with errors.",
                "Possible causes include wrong tool choice, missing context, permission errors or brittle workflow assumptions.",
                "Review failed trace timelines and convert recurring causes into FailureCase / EvolutionProposal artifacts.",
                "Reduces repeated runtime failures by grounding improvements in trace evidence.",
                "review-foundation-traces", failed >= 3 ? "HIGH" : "MEDIUM", withSource(metrics, "foundation:agent-trace"), now));
        }
        if (handoffs > 0) {
            insights.add(record("foundation-human-handoffs", workspaceId, "Human handoffs are present in foundation traces",
                "Recent traces contain " + handoffs + " human handoff step(s).",
                "The agent likely encountered ambiguity, approval boundaries or insufficient confidence.",
                "Inspect handoff reasons and decide whether to improve prompts, knowledge, tools or approval rules.",
                "Clarifies which work should remain human-governed versus automated.",
                "review-handoff-reasons", "MEDIUM", withSource(metrics, "foundation:agent-trace"), now));
        }
        if (totalCost > 0 || totalTokens > 0) {
            insights.add(record("foundation-trace-cost", workspaceId, "Foundation trace cost/token footprint observed",
                "Recent traces used " + totalTokens + " token(s) with estimated cost $" + String.format("%.4f", totalCost) + ".",
                "High cost may come from long context, repeated tool loops or verbose reasoning traces.",
                "Use this as a baseline before optimizing prompt context and tool routing.",
                "Prevents optimization proposals from ignoring runtime cost evidence.",
                "monitor-cost-baseline", "INFO", withSource(metrics, "foundation:agent-trace"), now));
        }
        return insights;
    }

    private List<BusinessInsightRecord> evalInsights(String workspaceId, List<AgentEvaluation.EvalResult> evals, Instant now) {
        if (evals.isEmpty()) return List.of();
        List<BusinessInsightRecord> insights = new ArrayList<>();
        Map<String, Object> metrics = baseMetrics(List.of(), evals, Map.of());
        long failed = evals.stream().filter(eval -> !eval.isPassed()).count();
        double avgComposite = evals.stream().mapToDouble(AgentEvaluation.EvalResult::getCompositeScore).average().orElse(0.0);
        List<String> weakAgents = evals.stream()
            .filter(eval -> !eval.isPassed() || eval.getCompositeScore() < 0.7)
            .map(AgentEvaluation.EvalResult::getAgentId)
            .distinct()
            .toList();

        if (failed > 0 || avgComposite < 0.75) {
            insights.add(record("foundation-eval-regression", workspaceId, "Foundation eval results need attention",
                "Evaluation data shows " + failed + " failed eval(s), average composite score " + String.format("%.2f", avgComposite) + ".",
                "Possible causes include prompt drift, tool-choice weakness, safety/completeness gaps or missing skills.",
                "Review weak agents " + weakAgents + " and create targeted evolution proposals backed by eval evidence.",
                "Improves agent changes with measurable pre/post evaluation signals.",
                "review-agent-evals", failed > 0 ? "HIGH" : "MEDIUM", withSource(metrics, "foundation:agent-evaluation"), now));
        }
        return insights;
    }

    private List<BusinessInsightRecord> evolutionInsights(String workspaceId, Map<String, Object> evolution, Instant now) {
        if (evolution.isEmpty()) return List.of();
        List<BusinessInsightRecord> insights = new ArrayList<>();
        int totalFailures = toInt(evolution.get("total_failures"));
        int resolved = toInt(evolution.get("resolved"));
        int pendingSuggestions = toInt(evolution.get("pending_suggestions"));
        Map<String, Object> metrics = new LinkedHashMap<>(evolution);
        metrics.put("source", "foundation:self-evolution");

        if (totalFailures > resolved) {
            insights.add(record("foundation-evolution-backlog", workspaceId, "Self-evolution has unresolved failure learning",
                "SelfEvolutionEngine reports " + totalFailures + " total failure(s), " + resolved + " resolved.",
                "Repeated runtime/eval failures may not yet be converted into operational improvements.",
                "Review root cause distribution and create governed proposal/delegated-task follow-ups.",
                "Closes the loop from failure evidence to approved team or prompt improvements.",
                "review-evolution-backlog", totalFailures - resolved >= 3 ? "HIGH" : "MEDIUM", metrics, now));
        }
        if (pendingSuggestions > 0) {
            insights.add(record("foundation-evolution-suggestions", workspaceId, "Self-evolution has pending suggestions",
                "SelfEvolutionEngine reports " + pendingSuggestions + " pending suggestion(s).",
                "There are candidate skill/policy improvements waiting for business review.",
                "Project suggestions into business approval/proposal workflows before applying changes.",
                "Keeps optimization governed instead of autonomous.",
                "review-evolution-suggestions", "MEDIUM", metrics, now));
        }
        return insights;
    }

    private Map<String, Object> baseMetrics(List<AgentTrace> traces, List<AgentEvaluation.EvalResult> evals, Map<String, Object> evolution) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("traceCount", traces.size());
        metrics.put("failedTraceCount", traces.stream().filter(this::isFailedTrace).count());
        metrics.put("traceErrorCount", traces.stream().mapToInt(AgentTrace::getErrorCount).sum());
        metrics.put("traceTokens", traces.stream().mapToLong(AgentTrace::getTotalTokens).sum());
        metrics.put("traceEstimatedCost", traces.stream().mapToDouble(AgentTrace::getEstimatedCost).sum());
        metrics.put("evalCount", evals.size());
        metrics.put("failedEvalCount", evals.stream().filter(eval -> !eval.isPassed()).count());
        metrics.put("avgEvalComposite", evals.stream().mapToDouble(AgentEvaluation.EvalResult::getCompositeScore).average().orElse(0.0));
        metrics.put("evolutionSummary", evolution);
        return metrics;
    }

    private Map<String, Object> withSource(Map<String, Object> metrics, String source) {
        Map<String, Object> copy = new LinkedHashMap<>(metrics);
        copy.put("source", source);
        return copy;
    }

    private List<Map<String, Object>> nextActions(List<BusinessInsightRecord> insights) {
        return insights.stream()
            .map(insight -> {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("id", insight.getSuggestedAction());
                action.put("title", insight.getTitle());
                action.put("description", insight.getRecommendation());
                action.put("severity", insight.getSeverity());
                return action;
            })
            .collect(Collectors.toList());
    }

    private BusinessInsightRecord record(String id, String workspaceId, String title, String finding,
                                         String possibleCause, String recommendation, String expectedBenefit,
                                         String suggestedAction, String severity, Map<String, Object> metrics, Instant now) {
        return new BusinessInsightRecord()
            .setInsightId(id)
            .setWorkspaceId(workspaceId != null ? workspaceId : "")
            .setTitle(title)
            .setFinding(finding)
            .setPossibleCause(possibleCause)
            .setRecommendation(recommendation)
            .setExpectedBenefit(expectedBenefit)
            .setSuggestedAction(suggestedAction)
            .setSeverity(severity)
            .setMetrics(metrics)
            .setGeneratedAt(now);
    }

    private boolean isFailedTrace(AgentTrace trace) {
        return trace.getStatus() == AgentTrace.Status.FAILED
            || trace.getStatus() == AgentTrace.Status.CANCELLED
            || trace.getStatus() == AgentTrace.Status.TIMED_OUT
            || trace.getStatus() == AgentTrace.Status.PARTIAL;
    }

    private boolean hasHumanHandoff(AgentTrace trace) {
        return trace.getSteps().stream().anyMatch(step -> step.type() == AgentTrace.StepType.HUMAN_HANDOFF);
    }

    private int toInt(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        String text = String.valueOf(value).replace("%", "").trim();
        if (text.isBlank()) return 0;
        try { return Integer.parseInt(text); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
