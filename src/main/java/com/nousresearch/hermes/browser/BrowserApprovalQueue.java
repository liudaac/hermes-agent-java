package com.nousresearch.hermes.browser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory tenant-level approval queue for sensitive browser actions. */
public class BrowserApprovalQueue {
    private static final int MAX = 500;
    private final String tenantId;
    private final ConcurrentHashMap<String, BrowserApprovalRequest> requests = new ConcurrentHashMap<>();

    public BrowserApprovalQueue(String tenantId) {
        this.tenantId = tenantId;
    }

    public BrowserApprovalRequest create(BrowserAction action, Map<String, Object> rawArgs, String denyReason) {
        String id = "bap-" + UUID.randomUUID().toString().substring(0, 10);
        Map<String, Object> args = new LinkedHashMap<>(rawArgs);
        BrowserApprovalRequest request = new BrowserApprovalRequest(
            id, tenantId, action, args, denyReason, BrowserApprovalRequest.Status.PENDING,
            Instant.now(), null, null, null
        );
        requests.put(id, request);
        trim();
        return request;
    }

    public BrowserApprovalRequest get(String id) { return requests.get(id); }

    public List<BrowserApprovalRequest> list(int limit) {
        return requests.values().stream()
            .sorted(Comparator.comparing(BrowserApprovalRequest::createdAt).reversed())
            .limit(Math.max(1, limit))
            .toList();
    }

    public BrowserApprovalRequest update(String id, BrowserApprovalRequest.Status status, String actor, String reason) {
        return requests.computeIfPresent(id, (k, v) -> v.withStatus(status, actor, reason));
    }

    private void trim() {
        if (requests.size() <= MAX) return;
        List<BrowserApprovalRequest> oldest = new ArrayList<>(requests.values());
        oldest.sort(Comparator.comparing(BrowserApprovalRequest::createdAt));
        for (int i = 0; i < oldest.size() - MAX; i++) requests.remove(oldest.get(i).id());
    }
}
