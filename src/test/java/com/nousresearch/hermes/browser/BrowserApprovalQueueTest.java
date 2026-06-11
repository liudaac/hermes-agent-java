package com.nousresearch.hermes.browser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrowserApprovalQueueTest {

    @Test
    void approvalQueuePersistsAndReloadsPendingApprovals() throws Exception {
        Path dir = Files.createTempDirectory("browser-approval-queue-");
        Path store = dir.resolve("browser-approvals.json");
        BrowserApprovalQueue queue = new BrowserApprovalQueue("tenant-a", store, Duration.ofMinutes(30));
        var request = queue.create(
            new BrowserAction("click", "s1", "https://example.com", "Delete button", null, null, "agent", "needs approval"),
            Map.of("action", "click", "session_id", "s1", "target", "Delete button"),
            "requires explicit confirmation"
        );

        assertTrue(Files.exists(store));
        BrowserApprovalQueue reloaded = new BrowserApprovalQueue("tenant-a", store, Duration.ofMinutes(30));
        var loaded = reloaded.get(request.id());
        assertNotNull(loaded);
        assertEquals(BrowserApprovalRequest.Status.PENDING, loaded.status());
        assertEquals("click", loaded.action().action());
        assertEquals("s1", loaded.action().sessionId());
        assertEquals("Delete button", loaded.action().target());
        assertNotNull(loaded.expiresAt());
    }

    @Test
    void approvalQueueExpiresPendingApprovalsAndSupportsStatusFilter() throws Exception {
        Path dir = Files.createTempDirectory("browser-approval-queue-expire-");
        BrowserApprovalQueue queue = new BrowserApprovalQueue("tenant-b", dir.resolve("browser-approvals.json"), Duration.ofMillis(1));
        var request = queue.create(
            new BrowserAction("submit", null, "https://example.com", "Submit", null, null, "agent", "short ttl"),
            Map.of("action", "submit"),
            "requires explicit confirmation"
        );
        Thread.sleep(10);
        assertEquals(BrowserApprovalRequest.Status.EXPIRED, queue.get(request.id()).status());
        assertTrue(queue.list(10, BrowserApprovalRequest.Status.PENDING).isEmpty());
        assertEquals(1, queue.list(10, BrowserApprovalRequest.Status.EXPIRED).size());

        BrowserApprovalQueue reloaded = new BrowserApprovalQueue("tenant-b", dir.resolve("browser-approvals.json"), Duration.ofMinutes(30));
        assertEquals(BrowserApprovalRequest.Status.EXPIRED, reloaded.get(request.id()).status());
    }
}
