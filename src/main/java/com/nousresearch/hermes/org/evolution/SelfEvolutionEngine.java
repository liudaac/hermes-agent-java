package com.nousresearch.hermes.org.evolution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-evolution engine — enables AI agents to learn from their
 * failures and improve autonomously.
 *
 * <p>Three evolution mechanisms:</p>
 * <ol>
 *   <li><b>Failure Library</b> — catalog of errors with root cause analysis and corrective actions</li>
 *   <li><b>Skill Suggestion</b> — detect repeated patterns and suggest new skills</li>
 *   <li><b>Prompt Optimization</b> — refine system prompts based on success/failure patterns</li>
 *   <li><b>Cross-Agent Learning</b> — share lessons across agents with similar roles</li>
 * </ol>
 */
public class SelfEvolutionEngine {
    private static final Logger logger = LoggerFactory.getLogger(SelfEvolutionEngine.class);

    /** All failure cases across the organization. */
    private final ConcurrentHashMap<String, FailureCase> failureLibrary = new ConcurrentHashMap<>();

    /** Success patterns to reinforce. */
    private final ConcurrentHashMap<String, List<String>> successPatterns = new ConcurrentHashMap<>();

    /** Skill suggestions pending review. */
    private final ConcurrentHashMap<String, SkillSuggestion> suggestions = new ConcurrentHashMap<>();

    /** Evolution history log. */
    private final ConcurrentHashMap<String, List<EvolutionEvent>> history = new ConcurrentHashMap<>();

    /** Maximum failure cases to retain. */
    private static final int MAX_FAILURES = 500;

    // ---- failure recording -------

    /** Record a failure case for learning. */
    public FailureCase recordFailure(FailureCase failure) {
        failureLibrary.put(failure.getId(), failure);
        if (failureLibrary.size() > MAX_FAILURES) {
            // Prune oldest
            String oldest = failureLibrary.values().stream()
                .min(Comparator.comparing(FailureCase::getOccurredAt))
                .map(FailureCase::getId).orElse(null);
            if (oldest != null) failureLibrary.remove(oldest);
        }
        logEvolution(failure.getAgentId(), new EvolutionEvent(EvolutionEvent.Type.FAILURE_RECORDED,
            failure.getRootCause().name() + ": " + failure.getLesson()));
        return failure;
    }

    /** Mark a failure as resolved (corrective actions implemented). */
    public void resolve(String failureId, List<String> implementedActions) {
        FailureCase fc = failureLibrary.get(failureId);
        if (fc != null) {
            // Create resolved version
            FailureCase resolved = new FailureCase.Builder(fc.getAgentId(), fc.getTaskDescription(), fc.getActualOutcome())
                .id(fc.getId()).expectedOutcome(fc.getExpectedOutcome())
                .rootCause(fc.getRootCause()).severity(fc.getSeverity())
                .lesson(fc.getLesson()).resolved(true).occurredAt(fc.getOccurredAt()).build();
            implementedActions.forEach(resolved.getCorrectiveActions()::add);
            failureLibrary.put(failureId, resolved);
        }
    }

    // ---- pattern detection -------

    /** Detect repeated failure patterns across an agent's history. */
    public List<FailurePattern> detectPatterns(String agentId, int minOccurrences) {
        List<FailureCase> agentFailures = failureLibrary.values().stream()
            .filter(f -> f.getAgentId().equals(agentId)).toList();

        Map<FailureCase.RootCause, Long> counts = new LinkedHashMap<>();
        for (FailureCase f : agentFailures) {
            counts.merge(f.getRootCause(), 1L, Long::sum);
        }

        List<FailurePattern> patterns = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            if (entry.getValue() >= minOccurrences) {
                patterns.add(new FailurePattern(entry.getKey(), entry.getValue(),
                    String.format("Agent %s has %d failures with root cause %s", agentId, entry.getValue(), entry.getKey())));
            }
        }
        return patterns;
    }

    /** Detect patterns across all agents (org-wide). */
    public List<FailurePattern> detectOrgPatterns(int minOccurrences) {
        Map<FailureCase.RootCause, Long> counts = new LinkedHashMap<>();
        for (FailureCase f : failureLibrary.values()) {
            counts.merge(f.getRootCause(), 1L, Long::sum);
        }
        return counts.entrySet().stream()
            .filter(e -> e.getValue() >= minOccurrences)
            .map(e -> new FailurePattern(e.getKey(), e.getValue(),
                String.format("Org-wide: %d failures with root cause %s", e.getValue(), e.getKey())))
            .toList();
    }

    // ---- skill suggestion -------

    /** Generate skill suggestions based on failure patterns. */
    public SkillSuggestion suggestSkill(String agentId) {
        List<FailurePattern> patterns = detectPatterns(agentId, 2);
        if (patterns.isEmpty()) return null;

        FailurePattern top = patterns.get(0);
        String skillName = switch (top.rootCause()) {
            case WRONG_TOOL -> "tool-selection-guide";
            case INSUFFICIENT_CONTEXT -> "context-gathering-protocol";
            case HALLUCINATION -> "fact-verification-checklist";
            case PERMISSION_DENIED -> "permission-escalation-flow";
            case AMBIGUOUS_PROMPT -> "task-clarification-template";
            default -> "error-recovery-" + top.rootCause().name().toLowerCase();
        };

        SkillSuggestion sg = new SkillSuggestion(agentId, skillName,
            String.format("Auto-generated from %d failures: %s", top.occurrences(), top.rootCause()));
        suggestions.put(skillName, sg);
        return sg;
    }

    // ---- success reinforcement -------

    /** Record a successful pattern. */
    public void recordSuccess(String agentId, String pattern, String description) {
        successPatterns.computeIfAbsent(agentId, k -> new ArrayList<>()).add(pattern);
        logEvolution(agentId, new EvolutionEvent(EvolutionEvent.Type.SUCCESS_REINFORCED,
            pattern + ": " + description));
    }

    /** Get successful patterns for an agent to reinforce in prompts. */
    public List<String> getSuccessPatterns(String agentId) {
        return successPatterns.getOrDefault(agentId, List.of());
    }

    // ---- cross-agent sharing -------

    /** Share a lesson from one agent to another with similar role. */
    public void shareLesson(String fromAgentId, String toAgentId, String failureId) {
        FailureCase fc = failureLibrary.get(failureId);
        if (fc == null) return;
        logEvolution(toAgentId, new EvolutionEvent(EvolutionEvent.Type.LESSON_SHARED,
            "Learned from " + fromAgentId + ": " + fc.getLesson()));
        logger.info("Shared lesson from {} to {}: {}", fromAgentId, toAgentId, fc.getLesson());
    }

    /** Build an evolution-aware system prompt for an agent. */
    public String buildEvolutionPrompt(String agentId) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n# Self-Evolution Context\n");

        // Recent lessons
        List<FailureCase> recent = failureLibrary.values().stream()
            .filter(f -> f.getAgentId().equals(agentId) && f.isResolved())
            .sorted(Comparator.comparing(FailureCase::getOccurredAt).reversed())
            .limit(3).toList();

        if (!recent.isEmpty()) {
            sb.append("## Recent Lessons Learned\n");
            for (FailureCase fc : recent) {
                sb.append(fc.toPromptInjection()).append("\n");
            }
        }

        // Success patterns
        List<String> successes = getSuccessPatterns(agentId);
        if (!successes.isEmpty()) {
            sb.append("## Proven Successful Approaches\n");
            for (String s : successes) sb.append("- ").append(s).append("\n");
        }

        return sb.toString();
    }

    // ---- queries -------

    public List<FailureCase> getAgentFailures(String agentId, int limit) {
        return failureLibrary.values().stream()
            .filter(f -> f.getAgentId().equals(agentId))
            .sorted(Comparator.comparing(FailureCase::getOccurredAt).reversed())
            .limit(limit).toList();
    }

    public long getTotalFailures() { return failureLibrary.size(); }
    public long getResolvedFailures() { return failureLibrary.values().stream().filter(FailureCase::isResolved).count(); }
    public double getResolutionRate() {
        return failureLibrary.isEmpty() ? 0 : (double) getResolvedFailures() / failureLibrary.size();
    }

    /** Get most common root causes across the org. */
    public Map<FailureCase.RootCause, Long> getRootCauseDistribution() {
        Map<FailureCase.RootCause, Long> dist = new LinkedHashMap<>();
        for (FailureCase fc : failureLibrary.values()) {
            dist.merge(fc.getRootCause(), 1L, Long::sum);
        }
        return dist;
    }

    /** Evolution events for an agent. */
    public List<EvolutionEvent> getHistory(String agentId, int limit) {
        return history.getOrDefault(agentId, List.of()).stream().limit(limit).toList();
    }

    /** Summary for dashboard. */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total_failures", getTotalFailures());
        s.put("resolved", getResolvedFailures());
        s.put("resolution_rate", String.format("%.0f%%", getResolutionRate() * 100));
        s.put("root_causes", getRootCauseDistribution());
        s.put("pending_suggestions", suggestions.size());
        s.put("success_patterns", successPatterns.values().stream().mapToInt(List::size).sum());
        return s;
    }

    // ---- internal -------

    private void logEvolution(String agentId, EvolutionEvent event) {
        history.computeIfAbsent(agentId, k -> new ArrayList<>()).add(event);
    }

    public record FailurePattern(FailureCase.RootCause rootCause, long occurrences, String detail) {}

    public record SkillSuggestion(String agentId, String skillName, String rationale) {}

    public record EvolutionEvent(Type type, String detail) {
        public enum Type { FAILURE_RECORDED, SUCCESS_REINFORCED, LESSON_SHARED, PATTERN_DETECTED, SKILL_SUGGESTED }
    }
}
