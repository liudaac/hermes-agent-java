package com.nousresearch.hermes.business.foundation;

import com.nousresearch.hermes.blueprint.FoundationCapabilityValidationReport;
import com.nousresearch.hermes.blueprint.FoundationCapabilityValidator;
import com.nousresearch.hermes.blueprint.TeamBlueprintCompileResult;
import com.nousresearch.hermes.blueprint.TeamBlueprintCompiler;
import com.nousresearch.hermes.blueprint.TeamBlueprintRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalAdapter;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.run.BusinessRunProjectionAdapter;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.insight.BusinessEvalRunProjectionAdapter;
import com.nousresearch.hermes.business.insight.BusinessInsightProjectionAdapter;
import com.nousresearch.hermes.business.insight.BusinessInsightRecord;
import com.nousresearch.hermes.business.insight.BusinessInsightSummary;
import com.nousresearch.hermes.collaboration.DelegatedTask;
import com.nousresearch.hermes.collaboration.DelegatedTaskEnvelope;
import com.nousresearch.hermes.collaboration.IntentOrchestrator;
import com.nousresearch.hermes.evolution.EvolutionProposalAdapter;
import com.nousresearch.hermes.evolution.EvolutionProposalRecord;
import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.org.eval.AgentEvaluation;
import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.prompt.PromptAssetResolver;
import com.nousresearch.hermes.prompt.PromptContext;
import com.nousresearch.hermes.scenario.ScenarioIntentAdapter;
import com.nousresearch.hermes.scenario.ScenarioIntentRequest;
import com.nousresearch.hermes.scenario.ScenarioRecord;

import java.util.List;
import java.util.Objects;

/**
 * Thin integration boundary for Business Portal -> Hermes foundation adapters.
 *
 * <p>This facade is intentionally boring: it composes existing adapters and
 * exposes foundation-grounded operations without adding product routes, UI or a
 * new runtime. Future Business Portal API/UI/generation code should depend on
 * this boundary instead of calling low-level services directly.</p>
 */
public class BusinessPortalFoundationFacade {
    private final PromptAssetResolver promptAssetResolver;
    private final FoundationCapabilityValidator capabilityValidator;
    private final TeamBlueprintCompiler teamBlueprintCompiler;
    private final ScenarioIntentAdapter scenarioIntentAdapter;
    private final BusinessRunProjectionAdapter runProjectionAdapter;
    private final BusinessApprovalAdapter approvalAdapter;
    private final BusinessInsightProjectionAdapter insightProjectionAdapter;
    private final BusinessEvalRunProjectionAdapter evalRunProjectionAdapter;
    private final EvolutionProposalAdapter evolutionProposalAdapter;
    private final BusinessPortalFoundationDiagnostics diagnostics = new BusinessPortalFoundationDiagnostics();

    public BusinessPortalFoundationFacade(PromptAssetResolver promptAssetResolver,
                                          FoundationCapabilityValidator capabilityValidator,
                                          TeamBlueprintCompiler teamBlueprintCompiler,
                                          ScenarioIntentAdapter scenarioIntentAdapter,
                                          BusinessRunProjectionAdapter runProjectionAdapter,
                                          BusinessApprovalAdapter approvalAdapter,
                                          BusinessInsightProjectionAdapter insightProjectionAdapter,
                                          BusinessEvalRunProjectionAdapter evalRunProjectionAdapter,
                                          EvolutionProposalAdapter evolutionProposalAdapter) {
        this.promptAssetResolver = Objects.requireNonNull(promptAssetResolver, "promptAssetResolver");
        this.capabilityValidator = Objects.requireNonNull(capabilityValidator, "capabilityValidator");
        this.teamBlueprintCompiler = Objects.requireNonNull(teamBlueprintCompiler, "teamBlueprintCompiler");
        this.scenarioIntentAdapter = Objects.requireNonNull(scenarioIntentAdapter, "scenarioIntentAdapter");
        this.runProjectionAdapter = Objects.requireNonNull(runProjectionAdapter, "runProjectionAdapter");
        this.approvalAdapter = Objects.requireNonNull(approvalAdapter, "approvalAdapter");
        this.insightProjectionAdapter = Objects.requireNonNull(insightProjectionAdapter, "insightProjectionAdapter");
        this.evalRunProjectionAdapter = Objects.requireNonNull(evalRunProjectionAdapter, "evalRunProjectionAdapter");
        this.evolutionProposalAdapter = Objects.requireNonNull(evolutionProposalAdapter, "evolutionProposalAdapter");
    }

    public PromptContext resolvePromptContext(String workspaceId, List<String> promptRefs, String taskContext,
                                              PromptAssetResolver.ResolveOptions options) {
        return promptAssetResolver.resolve(workspaceId, promptRefs, taskContext, options);
    }

    public FoundationCapabilityValidationReport validateTeamBlueprint(String workspaceId, TeamBlueprintRecord teamBlueprint) {
        return capabilityValidator.validateTeamBlueprint(workspaceId, teamBlueprint);
    }

    public TeamBlueprintCompileResult compileTeamBlueprint(String workspaceId, TeamBlueprintRecord teamBlueprint) {
        return teamBlueprintCompiler.compileActiveVersion(workspaceId, teamBlueprint);
    }

    public ScenarioIntentRequest buildScenarioIntentRequest(ScenarioRecord scenario, String userInput) {
        return scenarioIntentAdapter.toIntentRequest(scenario, userInput);
    }

    public IntentOrchestrator.IntentPlan planScenarioIntent(ScenarioRecord scenario, String userInput) {
        return scenarioIntentAdapter.plan(scenario, userInput);
    }

    public IntentOrchestrator.IntentRun executeScenarioIntent(ScenarioRecord scenario, String userInput) {
        return scenarioIntentAdapter.execute(scenario, userInput);
    }

    public BusinessRunRecord projectIntentRun(String workspaceId, String scenarioId, String scenarioName,
                                              IntentOrchestrator.IntentRun run) {
        return runProjectionAdapter.fromIntentRun(workspaceId, scenarioId, scenarioName, run);
    }

    public BusinessRunRecord projectIntentRun(String workspaceId, String scenarioId, String scenarioName,
                                              IntentOrchestrator.IntentRun run, List<AgentTrace> traces) {
        return runProjectionAdapter.fromIntentRun(workspaceId, scenarioId, scenarioName, run, traces);
    }

    public BusinessInsightSummary projectFoundationInsights(String workspaceId, List<AgentTrace> traces,
                                                             List<AgentEvaluation.EvalResult> evalResults,
                                                             java.util.Map<String, Object> evolutionSummary) {
        return insightProjectionAdapter.fromFoundationSignals(workspaceId, traces, evalResults, evolutionSummary);
    }

    public List<BusinessInsightRecord> projectTraceInsights(String workspaceId, List<AgentTrace> traces) {
        return insightProjectionAdapter.fromTraces(workspaceId, traces);
    }

    public List<BusinessInsightRecord> projectEvalInsights(String workspaceId, List<AgentEvaluation.EvalResult> evalResults) {
        return insightProjectionAdapter.fromEvalResults(workspaceId, evalResults);
    }

    public List<BusinessInsightRecord> projectEvolutionInsights(String workspaceId, java.util.Map<String, Object> evolutionSummary) {
        return insightProjectionAdapter.fromEvolutionSummary(workspaceId, evolutionSummary);
    }

    public BusinessEvalRunProjectionAdapter.BusinessEvalRunProjection projectEvalRun(String workspaceId, com.nousresearch.hermes.org.eval.AgentEvaluation.EvalResult result) {
        return evalRunProjectionAdapter.fromEvalResult(workspaceId, result);
    }

    public List<BusinessEvalRunProjectionAdapter.BusinessEvalRunProjection> projectEvalRuns(String workspaceId, List<com.nousresearch.hermes.org.eval.AgentEvaluation.EvalResult> results) {
        return evalRunProjectionAdapter.fromEvalResults(workspaceId, results);
    }

    public FailureCase projectProposalFailureCase(EvolutionProposalRecord proposal) {
        return evolutionProposalAdapter.toFailureCase(proposal);
    }

    public DelegatedTaskEnvelope projectProposalDelegatedTaskEnvelope(EvolutionProposalRecord proposal) {
        return evolutionProposalAdapter.toDelegatedTaskEnvelope(proposal);
    }

    public FailureCase recordProposalLearning(EvolutionProposalRecord proposal) {
        return evolutionProposalAdapter.recordFailureLearning(proposal);
    }

    public BusinessApprovalRecord projectProposalApproval(EvolutionProposalRecord proposal) {
        return evolutionProposalAdapter.toBusinessApprovalCard(proposal);
    }

    public DelegatedTask createProposalReviewTask(EvolutionProposalRecord proposal) {
        return evolutionProposalAdapter.createDelegatedReviewTask(proposal);
    }

    public BusinessPortalFoundationDiagnostics.DiagnosticsReport diagnostics() {
        return diagnostics.inspect(this);
    }

    public PromptAssetResolver promptAssetResolver() { return promptAssetResolver; }
    public FoundationCapabilityValidator capabilityValidator() { return capabilityValidator; }
    public TeamBlueprintCompiler teamBlueprintCompiler() { return teamBlueprintCompiler; }
    public ScenarioIntentAdapter scenarioIntentAdapter() { return scenarioIntentAdapter; }
    public BusinessRunProjectionAdapter runProjectionAdapter() { return runProjectionAdapter; }
    public BusinessApprovalAdapter approvalAdapter() { return approvalAdapter; }
    public BusinessInsightProjectionAdapter insightProjectionAdapter() { return insightProjectionAdapter; }
    public BusinessEvalRunProjectionAdapter evalRunProjectionAdapter() { return evalRunProjectionAdapter; }
    public EvolutionProposalAdapter evolutionProposalAdapter() { return evolutionProposalAdapter; }
}
