package com.nousresearch.hermes.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionLibraryTest {

    @TempDir
    Path tempDir;

    private LocalSessionLibrary library;
    private static final String TENANT = "test-tenant";
    private static final String USER_A = "userA";
    private static final String USER_B = "userB";

    @BeforeEach
    void setUp() {
        library = new LocalSessionLibrary(tempDir);
    }

    @Test
    void shouldSaveAndRetrieveAsset() {
        var asset = new SessionAsset(
                null, TENANT, USER_A, "session-1",
                "Deploy to staging", "A deployment session",
                SessionAsset.SessionStatus.COMPLETED,
                false, 0, null, List.of("deploy", "staging"), List.of(),
                System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis()
        );

        String id = library.saveAsset(asset);
        assertNotNull(id);

        var retrieved = library.getAsset(TENANT, "session-1");
        assertNotNull(retrieved);
        assertEquals("Deploy to staging", retrieved.title());
        assertEquals(USER_A, retrieved.userId());
        assertEquals(SessionAsset.SessionStatus.COMPLETED, retrieved.status());
    }

    @Test
    void shouldFilterByBookmarked() {
        library.saveAsset(assetFor("s1", USER_A, false, 0));
        library.saveAsset(assetFor("s2", USER_A, true, 0));
        library.saveAsset(assetFor("s3", USER_A, false, 0));

        var query = new SessionLibrary.SessionQuery(
                null, true, null, null, null, null, "updated");
        var result = library.querySessions(TENANT, USER_A, query, 0, 10);

        assertEquals(1, result.total());
        assertTrue(result.items().get(0).bookmarked());
    }

    @Test
    void shouldFilterByRating() {
        library.saveAsset(assetFor("s1", USER_A, false, 3));
        library.saveAsset(assetFor("s2", USER_A, false, 5));
        library.saveAsset(assetFor("s3", USER_A, false, 1));

        var query = new SessionLibrary.SessionQuery(
                null, null, 4, null, null, null, "rating");
        var result = library.querySessions(TENANT, USER_A, query, 0, 10);

        assertEquals(1, result.total());
        assertEquals(5, result.items().get(0).rating());
    }

    @Test
    void shouldIsolateByUser() {
        library.saveAsset(assetFor("s1", USER_A, false, 0));
        library.saveAsset(assetFor("s2", USER_B, false, 0));

        var query = SessionLibrary.SessionQuery.all();
        var resultA = library.querySessions(TENANT, USER_A, query, 0, 10);
        var resultB = library.querySessions(TENANT, USER_B, query, 0, 10);

        // User A sees their own + null-user assets
        assertTrue(resultA.items().stream().anyMatch(a -> USER_A.equals(a.userId())));
        assertFalse(resultA.items().stream().anyMatch(a -> USER_B.equals(a.userId())));

        assertTrue(resultB.items().stream().anyMatch(a -> USER_B.equals(a.userId())));
        assertFalse(resultB.items().stream().anyMatch(a -> USER_A.equals(a.userId())));
    }

    @Test
    void shouldBookmarkAndUnbookmark() {
        library.saveAsset(assetFor("s1", USER_A, false, 0));

        library.bookmark(TENANT, USER_A, "s1", "Great session!");
        var bookmarked = library.getAsset(TENANT, "s1");
        assertTrue(bookmarked.bookmarked());
        assertEquals("Great session!", bookmarked.userComment());

        library.unbookmark(TENANT, USER_A, "s1");
        var unbookmarked = library.getAsset(TENANT, "s1");
        assertFalse(unbookmarked.bookmarked());
    }

    @Test
    void shouldRateSession() {
        library.saveAsset(assetFor("s1", USER_A, false, 0));

        library.rate(TENANT, USER_A, "s1", 5, "Excellent");

        var rated = library.getAsset(TENANT, "s1");
        assertEquals(5, rated.rating());
        assertEquals("Excellent", rated.userComment());
    }

    @Test
    void shouldSearchByKeyword() {
        library.saveAsset(new SessionAsset(
                null, TENANT, USER_A, "s1",
                "Deploy to production", "Production deployment session",
                SessionAsset.SessionStatus.COMPLETED,
                false, 0, null, List.of(), List.of(),
                System.currentTimeMillis(), System.currentTimeMillis(), null
        ));
        library.saveAsset(new SessionAsset(
                null, TENANT, USER_A, "s2",
                "Code review", "Reviewed PR #123",
                SessionAsset.SessionStatus.COMPLETED,
                false, 0, null, List.of(), List.of(),
                System.currentTimeMillis(), System.currentTimeMillis(), null
        ));

        var results = library.searchSessions(TENANT, USER_A, "deploy");
        assertEquals(1, results.size());
        assertTrue(results.get(0).title().contains("Deploy"));

        var reviewResults = library.searchSessions(TENANT, USER_A, "review");
        assertEquals(1, reviewResults.size());
    }

    @Test
    void shouldUpdateAssetMetadata() {
        library.saveAsset(assetFor("s1", USER_A, false, 0));

        var update = new SessionLibrary.SessionAssetUpdate(
                "Updated Title", List.of("tag1", "tag2"), "My note",
                SessionAsset.SessionStatus.ARCHIVED);
        library.updateAsset(TENANT, "s1", update);

        var updated = library.getAsset(TENANT, "s1");
        assertEquals("Updated Title", updated.title());
        assertEquals(2, updated.tags().size());
        assertEquals("My note", updated.userComment());
        assertEquals(SessionAsset.SessionStatus.ARCHIVED, updated.status());
    }

    @Test
    void shouldPaginateResults() {
        for (int i = 0; i < 15; i++) {
            library.saveAsset(assetFor("s" + i, USER_A, false, 0));
        }

        var query = SessionLibrary.SessionQuery.all();
        var page1 = library.querySessions(TENANT, USER_A, query, 0, 10);
        var page2 = library.querySessions(TENANT, USER_A, query, 1, 10);

        assertEquals(10, page1.items().size());
        assertTrue(page1.hasNext());
        assertEquals(5, page2.items().size());
        assertFalse(page2.hasNext());
    }

    private SessionAsset assetFor(String sessionId, String userId, boolean bookmarked, int rating) {
        return new SessionAsset(
                null, TENANT, userId, sessionId,
                "Session " + sessionId, "Summary for " + sessionId,
                SessionAsset.SessionStatus.COMPLETED,
                bookmarked, rating, null, List.of(), List.of(),
                System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis()
        );
    }
}
