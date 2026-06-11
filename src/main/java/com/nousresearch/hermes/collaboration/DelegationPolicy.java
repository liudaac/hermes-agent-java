package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.tenant.core.TenantContext;

import java.util.Comparator;
import java.util.List;

/**
 * Safe advisory policy for deciding when orchestration should recommend a
 * delegated/fresh context. This policy never starts subprocesses or creates
 * external agents; it only annotates plans/runs for the caller/operator.
 */
public final class DelegationPolicy {
    private static final double RECOMMENDATION_THRESHOLD = 0.55;

    private DelegationPolicy() {}

    public static DelegationDecision evaluate(boolean allowDelegation, ContextPressureReport report, TenantContext tenantContext, String preferredTeamId) {
        ContextPressureReport safeReport = report != null ? report : ContextPressureReport.none();
        if (!allowDelegation) {
            return new DelegationDecision(false, "allow_delegation=false", safeReport, null, null);
        }
        if (safeReport.score() < RECOMMENDATION_THRESHOLD) {
            return DelegationDecision.notRecommended(safeReport);
        }

        String teamId = chooseTeamId(tenantContext, preferredTeamId);
        String profile = chooseProfile(safeReport);
        String reason = String.join("; ", safeReport.reasons());
        if (reason.isBlank()) {
            reason = "context pressure score " + String.format(java.util.Locale.ROOT, "%.2f", safeReport.score()) + " exceeds delegation threshold";
        }
        return new DelegationDecision(true, reason, safeReport, teamId, profile);
    }

    private static String chooseProfile(ContextPressureReport report) {
        if (report.criticalPath()) return "critical-path-reviewer";
        if (report.compacted() || report.nearLimit()) return "fresh-context-worker";
        if (report.highComplexity()) return "complex-task-decomposer";
        return "delegated-worker";
    }

    private static String chooseTeamId(TenantContext tenantContext, String preferredTeamId) {
        if (preferredTeamId != null && !preferredTeamId.isBlank()) return preferredTeamId;
        if (tenantContext == null) return null;
        try {
            List<Team> teams = tenantContext.getTeamManager().listTeams();
            return teams.stream()
                .max(Comparator.comparingInt(Team::size).thenComparing(Team::getLastActivity))
                .map(Team::getTeamId)
                .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
