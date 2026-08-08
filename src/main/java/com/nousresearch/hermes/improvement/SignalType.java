package com.nousresearch.hermes.improvement;

/**
 * Type of improvement signal collected from user behavior.
 *
 * <p>Each signal type carries an implicit weight and risk level
 * that determines how the self-improvement engine processes it.</p>
 */
public enum SignalType {
    /** User bookmarked a session (weight=0.6, LOW) */
    BOOKMARK,
    /** User rated a session >= 4 (weight=0.8, LOW, positive) */
    RATING_HIGH,
    /** User rated a session <= 2 (weight=1.0, LOW, negative) */
    RATING_LOW,
    /** User corrected the agent: "no, should be..." (weight=1.0, HIGH) */
    USER_CORRECTION,
    /** User is repeating a similar task (weight=0.5, MEDIUM) */
    REPEAT_PATTERN,
    /** User referenced a historical session (weight=0.7, MEDIUM) */
    SESSION_REFERENCE,
    /** User explicitly asked to remember something (weight=1.0, HIGH) */
    EXPLICIT_FEEDBACK
}
