package com.nousresearch.hermes.improvement;

/**
 * Routing scope for improvement signals.
 *
 * <p>Determines which layer of the three-layer architecture
 * (user -> space -> org) processes this signal.</p>
 */
public enum SignalScope {
    /** User-level: drives personal adaptation (preferences, frequent tools) */
    USER,
    /** Space-level: drives team evolution (template optimization, skill suggestions) */
    SPACE,
    /** Org-level: drives cross-space insights (best practices, resource allocation) */
    ORG
}
