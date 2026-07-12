package com.nousresearch.hermes.common;

import java.util.regex.Pattern;

/**
 * Shared path-safety utility for all file-backed repositories.
 *
 * <p>Earlier implementations silently {@link String#replaceAll replaced}
 * illegal characters with {@code _} before using the id as a path
 * component. That meant two distinct inputs (e.g. {@code "ab/c"} and
 * {@code "ab_c"}) could map to the same file — a silent collision
 * risk and a weak form of input leniency that lets callers pass
 * malformed ids without noticing. The strict validator here throws
 * {@link IllegalArgumentException} on the first bad character so
 * callers fail fast at the service boundary.
 *
 * <p>Two valid id shapes are supported:
 * <ul>
 *   <li>{@link #strictPathSegment} — 2-64 chars, starts with letter/digit,
 *       remainder {@code [a-zA-Z0-9._-]} (used for workspace/team/scenario/
 *       run/approval/... business ids that originate from user input).</li>
 *   <li>{@link #uuidLike} — 1-128 chars from the same safe set but no
 *       leading-char restriction (used for auto-generated UUIDs /
 *       checkpoint ids that may start with a digit).</li>
 * </ul>
 *
 * Both methods forbid:
 * <ul>
 *   <li>Empty / blank strings</li>
 *   <li>{@code .} or {@code ..} as the entire id (path traversal)</li>
 *   <li>Any character outside {@code [a-zA-Z0-9._-]}</li>
 *   <li>Embedded {@code /} or {@code \} (path separators)</li>
 *   <li>Null bytes or other control characters</li>
 * </ul>
 */
public final class PathIds {

    private PathIds() {}

    /** Max length for any business id (matches WorkspaceService.VALID_ID). */
    public static final int MAX_ID_LEN = 64;

    /**
     * Business id: start with letter/digit, remainder {@code [a-zA-Z0-9._-]},
     * length 2-64.
     */
    private static final Pattern STRICT_ID =
        Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1," + (MAX_ID_LEN - 1) + "}");

    /**
     * Auto-generated id (UUIDs, random tokens): same char class, no
     * leading-char restriction, length 1-128.
     */
    private static final Pattern UUID_LIKE =
        Pattern.compile("[a-zA-Z0-9._-]{1,128}");

    private static final String CHAR_CLASS_ERR =
        " must be 2-64 chars starting with letter/digit, containing only [a-zA-Z0-9._-]";

    /**
     * Strict validation for a user-facing business id (workspaceId, teamId,
     * scenarioId, runId, approvalId, proposalId, assetId, ...).
     *
     * @throws IllegalArgumentException if the id is null/blank/too short/
     *         contains illegal characters/is a traversal token.
     */
    public static String strictPathSegment(String id, String fieldName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String trimmed = id.trim();
        if (trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException(fieldName + " must not be a path traversal token");
        }
        if (!STRICT_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(fieldName + CHAR_CLASS_ERR);
        }
        return trimmed;
    }

    /**
     * Strict validation for an auto-generated id (UUIDs, checkpoint keys,
     * random tokens). Same char class but no leading-char requirement and
     * up to 128 chars.
     *
     * @throws IllegalArgumentException if the id is null/blank/contains
     *         illegal characters.
     */
    public static String uuidLike(String id, String fieldName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String trimmed = id.trim();
        if (trimmed.equals(".") || trimmed.equals("..")) {
            throw new IllegalArgumentException(fieldName + " must not be a path traversal token");
        }
        if (!UUID_LIKE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(fieldName
                + " must be 1-128 chars containing only [a-zA-Z0-9._-]");
        }
        return trimmed;
    }
}
