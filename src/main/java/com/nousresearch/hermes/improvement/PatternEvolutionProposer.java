package com.nousresearch.hermes.improvement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects pattern shifts in user behavior and generates evolution proposals.
 *
 * <p>When a user's behavior pattern changes (e.g., switching from one workflow
 * to another, or consistently referencing different types of sessions), this
 * engine generates an {@link ImprovementProposal} via the PROMPT flow.</p>
 */
public class PatternEvolutionProposer {

    private static final Logger logger = LoggerFactory.getLogger(PatternEvolutionProposer.class);

    private final SignalStore signalStore;
    private final ImprovementConfirmationFlow confirmationFlow;

    /** Minimum signals to detect a pattern shift */
    private static final int MIN_SIGNALS_FOR_PATTERN = 3;

    public PatternEvolutionProposer(SignalStore signalStore,
                                     ImprovementConfirmationFlow confirmationFlow) {
        this.signalStore = signalStore;
        this.confirmationFlow = confirmationFlow;
    }

    /**
     * Detect pattern shifts for a user and generate proposals.
     *
     * @return list of pending/require-confirm proposals
     */
    public List<ImprovementProposal> detectPatternShift(String tenantId, String userId) {
        List<ImprovementProposal> proposals = new ArrayList<>();

        // Check for repeat pattern emergence
        proposals.addAll(detectRepeatPatternShift(tenantId, userId));

        // Check for session reference pattern (user referencing same sessions)
        proposals.addAll(detectReferencePattern(tenantId, userId));

        // Check for correction frequency increase
        proposals.addAll(detectCorrectionIncrease(tenantId, userId));

        return proposals;
    }

    private List<ImprovementProposal> detectRepeatPatternShift(String tenantId, String userId) {
        int repeatCount = signalStore.countByType(tenantId, userId, SignalType.REPEAT_PATTERN);
        if (repeatCount < MIN_SIGNALS_FOR_PATTERN) {
            return List.of();
        }

        ImprovementProposal proposal = ImprovementProposal.pending(
                tenantId, userId,
                "Workflow pattern detected: repeated tasks",
                "User has repeated similar tasks " + repeatCount + " times. " +
                "This suggests a recurring workflow that could be templated.",
                "Create a reusable session template from the repeated task pattern",
                "Faster execution of common tasks, less back-and-forth",
                "Based on " + repeatCount + " REPEAT_PATTERN signals",
                0.7
        );
        return List.of(confirmationFlow.proposePending(proposal));
    }

    private List<ImprovementProposal> detectReferencePattern(String tenantId, String userId) {
        int refCount = signalStore.countByType(tenantId, userId, SignalType.SESSION_REFERENCE);
        if (refCount < MIN_SIGNALS_FOR_PATTERN) {
            return List.of();
        }

        ImprovementProposal proposal = ImprovementProposal.pending(
                tenantId, userId,
                "Reference pattern: user frequently references history",
                "User has referenced historical sessions " + refCount + " times. " +
                "They may benefit from session templates or quick-access bookmarks.",
                "Promote frequently referenced sessions to templates",
                "Quicker access to proven workflows",
                "Based on " + refCount + " SESSION_REFERENCE signals",
                0.6
        );
        return List.of(confirmationFlow.proposePending(proposal));
    }

    private List<ImprovementProposal> detectCorrectionIncrease(String tenantId, String userId) {
        int correctionCount = signalStore.countByType(tenantId, userId, SignalType.USER_CORRECTION);
        if (correctionCount < 5) {  // Higher threshold for correction increase
            return List.of();
        }

        ImprovementProposal proposal = ImprovementProposal.requireConfirm(
                tenantId, userId,
                "Accuracy concern: frequent corrections",
                "User has corrected the agent " + correctionCount + " times. " +
                "This may indicate a systematic accuracy issue that needs attention.",
                "Review and update the agent's knowledge base or default behavior",
                "Reduce user corrections, improve agent accuracy",
                "Based on " + correctionCount + " USER_CORRECTION signals",
                0.9
        );
        return List.of(confirmationFlow.requireConfirm(proposal));
    }
}
