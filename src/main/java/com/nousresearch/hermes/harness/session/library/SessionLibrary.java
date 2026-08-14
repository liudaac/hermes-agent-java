package com.nousresearch.hermes.harness.session.library;

import java.util.List;

/**
 * Library for querying, bookmarking, and managing session assets.
 *
 * <p>Provides user-dimension isolation: all queries are scoped by
 * tenantId + userId.</p>
 */
public interface SessionLibrary {

    // ══════════════════════════════════════════════════════════════════
    //  Query
    // ══════════════════════════════════════════════════════════════════

    /**
     * Paginated query of a user's session assets.
     *
     * @param tenantId tenant
     * @param userId   user (nullable for tenant-level)
     * @param query    filter criteria
     * @param page     0-based page number
     * @param size     page size
     * @return paginated results
     */
    PageResult<SessionAsset> querySessions(String tenantId, String userId,
                                            SessionQuery query, int page, int size);

    /**
     * Get a single session asset by session ID.
     */
    SessionAsset getAsset(String tenantId, String sessionId);

    /**
     * Full-text search across session content.
     */
    List<SessionAsset> searchSessions(String tenantId, String userId, String keyword);

    // ══════════════════════════════════════════════════════════════════
    //  Bookmark & Rating
    // ══════════════════════════════════════════════════════════════════

    /**
     * Bookmark a session.
     */
    void bookmark(String tenantId, String userId, String sessionId, String note);

    /**
     * Remove bookmark.
     */
    void unbookmark(String tenantId, String userId, String sessionId);

    /**
     * Rate a session (1-5 stars) with optional comment.
     */
    void rate(String tenantId, String userId, String sessionId, int rating, String comment);

    // ══════════════════════════════════════════════════════════════════
    //  Update
    // ══════════════════════════════════════════════════════════════════

    /**
     * Update session asset metadata (title, tags, etc.).
     */
    void updateAsset(String tenantId, String sessionId, SessionAssetUpdate update);

    /**
     * Create or update a session asset from a completed session.
     * Called when a session ends or on-demand.
     *
     * @param asset the asset to save (id may be null for new assets)
     * @return the saved asset ID
     */
    String saveAsset(SessionAsset asset);

    // ══════════════════════════════════════════════════════════════════
    //  Step extraction
    // ══════════════════════════════════════════════════════════════════

    /**
     * Get structured step summaries for a session.
     */
    List<SessionAsset.StepSummary> getSteps(String tenantId, String sessionId);

    // ══════════════════════════════════════════════════════════════════
    //  Supporting types
    // ══════════════════════════════════════════════════════════════════

    record SessionQuery(
            String status,
            Boolean bookmarkedOnly,
            Integer minRating,
            String tag,
            Long startTime,
            Long endTime,
            String orderBy  // "created" / "updated" / "rating"
    ) {
        public static SessionQuery all() {
            return new SessionQuery(null, null, null, null, null, null, "updated");
        }
    }

    record SessionAssetUpdate(
            String title,
            List<String> tags,
            String userComment,
            SessionAsset.SessionStatus status
    ) {}

    record PageResult<T>(
            List<T> items,
            int page,
            int size,
            int total,
            boolean hasNext
    ) {}
}
