package com.nousresearch.hermes.collaboration;

/**
 * Capabilities a delegated executor may request before doing work.
 *
 * <p>These values are declarative only. They do not grant access by
 * themselves; {@link DelegatedExecutorSafetyPolicy} validates requested
 * capabilities and future executors must refuse work when validation fails.</p>
 */
public enum DelegatedExecutorCapability {
    /** Read repository-local context needed to prepare a patch. */
    FILE_READ,

    /** Produce file changes as a patch/diff inside an isolated sandbox. */
    PATCH_WRITE,

    /** Run local shell commands such as build or test commands. */
    COMMAND_EXECUTION,

    /** Access external networks. */
    NETWORK_ACCESS,

    /** Drive or inspect a browser session. */
    BROWSER_ACCESS,

    /** Merge a produced patch back to the parent workspace without review. */
    AUTO_MERGE
}
