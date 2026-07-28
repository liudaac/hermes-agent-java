package com.nousresearch.hermes.dashboard.jarvis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JarvisHandler.StreamContext#canSee(String)} — the
 * per-client workspace filter that prevents cross-tenant data leak
 * through the Jarvis SSE stream.
 */
class JarvisStreamFilterTest {

    private static JarvisHandler.StreamContext ctx(String workspaceId, boolean allAccess) {
        // SseClient is only held by reference; null is fine for unit-testing
        // the canSee predicate which never touches the client.
        return new JarvisHandler.StreamContext("test", null, workspaceId, allAccess);
    }

    @Test
    @DisplayName("Workspace-scoped client only sees events for its own workspace")
    void workspaceScopedClientSeesOwnWorkspace() {
        var c = ctx("acme", false);
        assertTrue(c.canSee("acme"));
        assertFalse(c.canSee("globex"));
    }

    @Test
    @DisplayName("Workspace-scoped client does NOT see system-wide events (no workspaceId)")
    void workspaceScopedClientDoesNotSeeSystemWide() {
        var c = ctx("acme", false);
        assertFalse(c.canSee(null));
        assertFalse(c.canSee(""));
    }

    @Test
    @DisplayName("allAccess client sees events for every workspace")
    void allAccessClientSeesAllWorkspaces() {
        var c = ctx("acme", true);
        assertTrue(c.canSee("acme"));
        assertTrue(c.canSee("globex"));
        assertTrue(c.canSee("initech"));
    }

}
