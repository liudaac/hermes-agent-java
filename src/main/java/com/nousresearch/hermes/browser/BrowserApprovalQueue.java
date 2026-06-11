package com.nousresearch.hermes.browser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tenant-level approval queue for sensitive browser actions. */
public class BrowserApprovalQueue {
    private static final int MAX = 500;
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final String tenantId;
    private final Path storePath;
    private final Duration ttl;
    private final ConcurrentHashMap<String, BrowserApprovalRequest> requests = new ConcurrentHashMap<>();

    public BrowserApprovalQueue(String tenantId) {
        this(tenantId, null, DEFAULT_TTL);
    }

    public BrowserApprovalQueue(String tenantId, Path storePath) {
        this(tenantId, storePath, DEFAULT_TTL);
    }

    public BrowserApprovalQueue(String tenantId, Path storePath, Duration ttl) {
        this.tenantId = tenantId;
        this.storePath = storePath;
        this.ttl = ttl != null ? ttl : DEFAULT_TTL;
        load();
        expirePending();
    }

    public synchronized BrowserApprovalRequest create(BrowserAction action, Map<String, Object> rawArgs, String denyReason) {
        expirePending();
        String id = "bap-" + UUID.randomUUID().toString().substring(0, 10);
        Map<String, Object> args = new LinkedHashMap<>(rawArgs);
        Instant now = Instant.now();
        BrowserApprovalRequest request = new BrowserApprovalRequest(
            id, tenantId, action, args, denyReason, BrowserApprovalRequest.Status.PENDING,
            now, now.plus(ttl), null, null, null
        );
        requests.put(id, request);
        trim();
        save();
        return request;
    }

    public BrowserApprovalRequest get(String id) {
        expirePending();
        return requests.get(id);
    }

    public List<BrowserApprovalRequest> list(int limit) {
        return list(limit, null);
    }

    public List<BrowserApprovalRequest> list(int limit, BrowserApprovalRequest.Status status) {
        expirePending();
        return requests.values().stream()
            .filter(r -> status == null || r.status() == status)
            .sorted(Comparator.comparing(BrowserApprovalRequest::createdAt).reversed())
            .limit(Math.max(1, limit))
            .toList();
    }

    public synchronized BrowserApprovalRequest update(String id, BrowserApprovalRequest.Status status, String actor, String reason) {
        expirePending();
        BrowserApprovalRequest updated = requests.computeIfPresent(id, (k, v) -> v.withStatus(status, actor, reason));
        if (updated != null) save();
        return updated;
    }

    public synchronized int expirePending() {
        Instant now = Instant.now();
        int[] count = {0};
        requests.replaceAll((id, request) -> {
            if (request.isExpired(now)) {
                count[0]++;
                return request.expire();
            }
            return request;
        });
        if (count[0] > 0) save();
        return count[0];
    }

    private void trim() {
        if (requests.size() <= MAX) return;
        List<BrowserApprovalRequest> oldest = new ArrayList<>(requests.values());
        oldest.sort(Comparator.comparing(BrowserApprovalRequest::createdAt));
        for (int i = 0; i < oldest.size() - MAX; i++) requests.remove(oldest.get(i).id());
    }

    private void load() {
        if (storePath == null || !Files.exists(storePath)) return;
        try {
            List<Map<String, Object>> rows = MAPPER.readValue(storePath.toFile(), new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> row : rows) {
                BrowserApprovalRequest request = BrowserApprovalRequest.fromMap(row);
                if (request.id() != null && !request.id().isBlank()) requests.put(request.id(), request);
            }
        } catch (Exception ignored) {
            // Corrupt approval state should not block tenant startup; new approvals will rewrite the file.
        }
    }

    private synchronized void save() {
        if (storePath == null) return;
        try {
            Files.createDirectories(storePath.getParent());
            List<Map<String, Object>> rows = requests.values().stream()
                .sorted(Comparator.comparing(BrowserApprovalRequest::createdAt).reversed())
                .map(BrowserApprovalRequest::toMap)
                .toList();
            MAPPER.writeValue(storePath.toFile(), rows);
        } catch (IOException ignored) {
            // Approval queue persistence is best-effort; audit log still records governance events.
        }
    }
}
