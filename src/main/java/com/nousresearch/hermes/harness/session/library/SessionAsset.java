package com.nousresearch.hermes.harness.session.library;

import java.util.List;

/**
 * Session asset - an archived conversation with metadata for
 * user-dimension session management.
 *
 * <p>A SessionAsset is created when a session completes (or on-demand)
 * and provides a queryable, bookmarkable, referenceable view of a
 * past conversation.</p>
 *
 * @param id           asset ID (generated)
 * @param tenantId     tenant identifier
 * @param userId       owner user ID (nullable for tenant-level sessions)
 * @param sessionId    original session ID
 * @param title        user-defined or auto-generated title
 * @param summary      LLM-generated summary (3-5 sentences)
 * @param status       ACTIVE / COMPLETED / ARCHIVED
 * @param bookmarked   whether the user bookmarked this session
 * @param rating       user rating 1-5, 0 = unrated
 * @param userComment  user's note/comment
 * @param tags         user-defined tags
 * @param steps        structured step summaries
 * @param createdAt    creation timestamp (epoch millis)
 * @param updatedAt    last update timestamp
 * @param completedAt  completion timestamp (nullable)
 */
public record SessionAsset(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String title,
        String summary,
        SessionStatus status,
        boolean bookmarked,
        int rating,
        String userComment,
        List<String> tags,
        List<StepSummary> steps,
        long createdAt,
        long updatedAt,
        Long completedAt
) {

    public enum SessionStatus {
        ACTIVE, COMPLETED, ARCHIVED
    }

    /**
     * A single step in the session's execution flow.
     *
     * @param index     step number (0-based)
     * @param action    what was done
     * @param toolUsed  tool name (nullable)
     * @param result    result summary
     * @param keyStep   whether this is a critical step
     * @param timestamp when the step occurred
     */
    public record StepSummary(
            int index,
            String action,
            String toolUsed,
            String result,
            boolean keyStep,
            long timestamp
    ) {}
}
