package com.nousresearch.hermes.evolution;

import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.business.run.BusinessRunStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates evolution proposals from run history.
 *
 * <p>Analyzes failed or low-quality runs and proposes changes to the team
 * blueprint. Uses rule-based pattern detection (no LLM call needed for MVP).</p>
 *
 * <p>Detection patterns:</p>
 * <ul>
 *   <li>Repeated task failures — suggests adding instructions or tools</li>
 *   <li>High error rate on a specific subtask — suggests agent role refinement</li>
 *   <li>Tasks going to wrong agent — suggests routing rule updates</li>
 * </ul>
 */
public class EvolutionProposalGenerator {

    private static final Logger logger = LoggerFactory.getLogger(EvolutionProposalGenerator.class);

    private final EvolutionProposalService proposalService;
    private final BusinessRunService runService;

    /** Minimum failed runs for the same pattern before suggesting a proposal */
    private static final int MIN_FAILURES_FOR_PROPOSAL = 2;

    public EvolutionProposalGenerator(EvolutionProposalService proposalService, BusinessRunService runService) {
        this.proposalService = proposalService;
        this.runService = runService;
    }

    /**
     * Analyze recent runs for a team and generate proposals for recurring issues.
     *
     * @return list of newly created proposals
     */
    public List<EvolutionProposalRecord> generateFromRecentRuns(String workspaceId, String teamId, int limit) {
        List<EvolutionProposalRecord> newProposals = new ArrayList<>();

        // Get recent failed runs
        List<BusinessRunRecord> failedRuns = runService.listRuns(workspaceId, teamId,
            BusinessRunService.FAILED, limit);

        if (failedRuns.isEmpty()) {
            logger.debug("No failed runs found for team {}/{}", workspaceId, teamId);
            return List.of();
        }

        // Pattern 1: repeated failures in same subtask
        Map<String, List<BusinessRunRecord>> failurePatterns = groupByFailurePattern(failedRuns);

        for (var entry : failurePatterns.entrySet()) {
            String pattern = entry.getKey();
            List<BusinessRunRecord> runs = entry.getValue();

            if (runs.size() >= MIN_FAILURES_FOR_PROPOSAL) {
                // Skip if there's already an open proposal for this pattern
                if (hasOpenProposalForPattern(workspaceId, teamId, pattern)) {
                    continue;
                }

                EvolutionProposalRecord proposal = generateProposalForPattern(
                    workspaceId, teamId, pattern, runs);
                newProposals.add(proposal);
                logger.info("Generated evolution proposal '{}' for team {}/{} (pattern: {}, {} failures)",
                    proposal.getProposalId(), workspaceId, teamId, pattern, runs.size());
            }
        }

        return newProposals;
    }

    /**
     * Generate a proposal from a single run (e.g., when a human marks it as "needs improvement").
     */
    public EvolutionProposalRecord generateFromRun(String workspaceId, String teamId, String runId,
                                                     String finding, String proposedChange) {
        BusinessRunRecord run = runService.requireRun(workspaceId, runId);

        String title = finding != null && !finding.isBlank()
            ? finding
            : "Improve performance for " + run.getTaskTitle();

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("sourceRunId", runId);
        evidence.put("taskTitle", run.getTaskTitle());
        evidence.put("runStatus", run.getStatus());
        evidence.put("resultSummary", run.getResultSummary());
        if (run.getSteps() != null && !run.getSteps().isEmpty()) {
            evidence.put("stepCount", run.getSteps().size());
        }

        return proposalService.createProposal(
            workspaceId, null, run.getScenarioId(), teamId,
            null, title,
            finding != null ? finding : "Run " + runId + " was flagged for improvement.",
            proposedChange != null ? proposedChange : "Review and improve team configuration.",
            "Improve reliability and accuracy for this task type.",
            evidence,
            Map.of("source", "manual-flagged", "sourceRunId", runId)
        );
    }

    private Map<String, List<BusinessRunRecord>> groupByFailurePattern(List<BusinessRunRecord> runs) {
        Map<String, List<BusinessRunRecord>> patterns = new HashMap<>();

        for (BusinessRunRecord run : runs) {
            // Extract failure patterns from steps
            if (run.getSteps() != null) {
                for (BusinessRunStep step : run.getSteps()) {
                    if ("FAILED".equals(step.getStatus())) {
                        String pattern = step.getTitle() != null ? step.getTitle() : "unknown-failure";
                        patterns.computeIfAbsent(pattern, k -> new ArrayList<>()).add(run);
                    }
                }
            }

            // Also group by risk judgement keywords
            if (run.getRiskJudgement() != null && run.getRiskJudgement().toLowerCase().contains("review")) {
                patterns.computeIfAbsent("needs-human-review", k -> new ArrayList<>()).add(run);
            }
        }

        return patterns;
    }

    private boolean hasOpenProposalForPattern(String workspaceId, String teamId, String pattern) {
        List<EvolutionProposalRecord> openProposals = proposalService.listProposals(workspaceId, null);
        return openProposals.stream()
            .filter(p -> teamId.equals(p.getTeamId()))
            .filter(p -> !EvolutionProposalService.APPLIED.equals(p.getStatus())
                       && !EvolutionProposalService.REJECTED.equals(p.getStatus()))
            .anyMatch(p -> p.getTitle() != null && p.getTitle().toLowerCase().contains(pattern.toLowerCase()));
    }

    private EvolutionProposalRecord generateProposalForPattern(String workspaceId, String teamId,
                                                                String pattern, List<BusinessRunRecord> runs) {
        String title = "Fix repeated failures: " + pattern;
        String finding = String.format(
            "Pattern '%s' has failed %d times in recent runs. This indicates a systemic issue.",
            pattern, runs.size());

        // Build proposed change based on pattern
        String proposedChange = buildProposedChange(pattern, runs);

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("pattern", pattern);
        evidence.put("failureCount", runs.size());
        evidence.put("sampleRunIds", runs.stream()
            .map(BusinessRunRecord::getRunId)
            .limit(5)
            .toList());
        evidence.put("firstSeen", runs.get(runs.size() - 1).getCreatedAt() != null
            ? runs.get(runs.size() - 1).getCreatedAt().toString() : "unknown");
        evidence.put("lastSeen", runs.get(0).getCreatedAt() != null
            ? runs.get(0).getCreatedAt().toString() : "unknown");

        return proposalService.createProposal(
            workspaceId, null,
            runs.get(0).getScenarioId(),
            teamId,
            null,
            title,
            finding,
            proposedChange,
            "Reduce failure rate and improve reliability.",
            evidence,
            Map.of("source", "auto-detected", "pattern", pattern)
        );
    }

    private String buildProposedChange(String pattern, List<BusinessRunRecord> runs) {
        // Simple rule-based suggestions
        String lower = pattern.toLowerCase();

        if (lower.contains("classif") || lower.contains("分类")) {
            return """
                1. Review and expand the classifier agent's instructions with more specific categories
                2. Add examples of edge cases to the knowledge base
                3. Consider adding a second verification step for ambiguous cases
                """;
        }
        if (lower.contains("refund") || lower.contains("退款") || lower.contains("return")) {
            return """
                1. Strengthen refund policy knowledge for the specialist agent
                2. Add clearer escalation rules for high-value refunds
                3. Update approval thresholds in the policy engine
                """;
        }
        if (lower.contains("review") || lower.contains("review-needed")) {
            return """
                1. Reduce false positives by refining the escalation criteria
                2. Add more context to the approval card to speed up human review
                3. Consider auto-approving low-risk cases
                """;
        }

        return """
            1. Review the failing agent's instructions and knowledge
            2. Check if additional tools or skills are needed
            3. Consider adding more specific success criteria to the scenario
            """;
    }
}
