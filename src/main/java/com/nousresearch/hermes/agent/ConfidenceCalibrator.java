package com.nousresearch.hermes.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Calibrates the agent's expressed confidence in its answers.
 *
 * <p>The goal is to make the agent honest about uncertainty instead of
 * hallucinating with false certainty. This improves trust and gives the
 * user a chance to ask for verification when it matters.</p>
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li><b>Heuristic</b> (default): fast, rule-based scoring using signals
 *       like tool usage, hedge words, specificity.</li>
 *   <li><b>LLM</b> (optional): ask a small model to rate confidence; more
 *       accurate but adds latency.</li>
 * </ul>
 */
public class ConfidenceCalibrator {

    private static final Logger logger = LoggerFactory.getLogger(ConfidenceCalibrator.class);

    private final boolean enabled;
    private final double directThreshold;
    private final double cautionThreshold;

    public ConfidenceCalibrator() {
        var cfg = com.nousresearch.hermes.config.ConfigManager.getInstance();
        this.enabled = cfg.getBoolean("confidence.enabled", true);
        this.directThreshold = cfg.getDouble("confidence.direct_threshold", 0.80);
        this.cautionThreshold = cfg.getDouble("confidence.caution_threshold", 0.50);
    }

    /**
     * Calibrate the confidence of a response.
     *
     * @param response         the raw assistant text
     * @param toolsUsed        number of distinct tools invoked this turn
     * @param hasSearchResult  whether a web search or browser result was included
     * @return a {@link CalibrationResult} with score, action, and adjusted text
     */
    public CalibrationResult calibrate(String response, int toolsUsed, boolean hasSearchResult) {
        if (!enabled || response == null || response.isBlank()) {
            return new CalibrationResult(0.5, Action.DIRECT, response);
        }

        double score = computeHeuristicScore(response, toolsUsed, hasSearchResult);

        Action action;
        String adjusted;
        if (score >= directThreshold) {
            action = Action.DIRECT;
            adjusted = response;
        } else if (score >= cautionThreshold) {
            action = Action.CAUTION;
            adjusted = response + "\n\n_(Confidence: " + pct(score)
                + "% — based on available information; verify if critical.)_";
        } else {
            action = Action.VERIFY;
            adjusted = "⚠️ I'm not fully confident about this.\n\n"
                + "My current understanding (confidence " + pct(score) + "%):\n"
                + "> " + truncate(response, 280).replace("\n", "\n> ") + "\n\n"
                + "Would you like me to verify with a search, or do you have additional context?";
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Confidence calibrated: score={:.2f}, action={}, length={}",
                score, action, response.length());
        }
        return new CalibrationResult(score, action, adjusted);
    }

    // ------------------------------------------------------------------
    // Heuristic scoring
    // ------------------------------------------------------------------

    private double computeHeuristicScore(String response, int toolsUsed, boolean hasSearchResult) {
        double score = 0.55; // neutral baseline

        // Positive signals
        if (toolsUsed > 0) score += 0.10;
        if (toolsUsed >= 2) score += 0.05;
        if (hasSearchResult) score += 0.10;
        if (containsData(response)) score += 0.08;
        if (containsCodeBlock(response)) score += 0.05; // code is usually verifiable

        // Negative signals (hedge / uncertainty language)
        String lower = response.toLowerCase();
        if (hasAny(lower, "i think", "i believe", "maybe", "perhaps")) score -= 0.12;
        if (hasAny(lower, "probably", "likely")) score -= 0.04;
        if (hasAny(lower, "i'm not sure", "unclear", "ambiguous", "uncertain", "don't know")) score -= 0.20;
        if (hasAny(lower, " hallucination", "made up", "fabricated")) score -= 0.30; // self-detected
        if (response.contains("?") && lower.startsWith("i ")) score -= 0.10; // answering with a question

        // Excessive length without structure → slightly lower
        if (response.length() > 2000 && !response.contains("```")) score -= 0.05;

        return clamp(score);
    }

    private static boolean containsData(String s) {
        return s.matches(".*\\d{4}.*") || s.contains("http") || s.contains("@");
    }

    private static boolean containsCodeBlock(String s) {
        return s.contains("```");
    }

    private static boolean hasAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String pct(double v) {
        return String.valueOf((int) (v * 100));
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }

    // ------------------------------------------------------------------

    public enum Action {
        /** High confidence — return as-is. */
        DIRECT,
        /** Medium confidence — append a mild disclaimer. */
        CAUTION,
        /** Low confidence — ask for verification or offer to search. */
        VERIFY
    }

    public record CalibrationResult(double score, Action action, String adjustedText) {}
}
