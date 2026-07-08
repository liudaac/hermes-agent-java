package com.nousresearch.hermes.dashboard.jarvis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ApprovalBridge — thin approval gate for dangerous operations.
 *
 * MVP scope: in-memory registry. When the user approves or rejects, the
 * decision is stored under the approval id. Callers can poll the result
 * via {@link #checkDecision(String)}.
 *
 * NOTE: the real approval gate (design.md §17) lives in
 * {@code BusinessApprovalService} + the dashboard portal/ops
 * integration. That infrastructure does not yet exist in the Java
 * codebase — it is planned for the v1 milestone. Until then, this
 * class provides a best-effort stand-in so Jarvis can demonstrate
 * the approval flow end-to-end.
 */
public class ApprovalBridge {
    private static final Logger log = LoggerFactory.getLogger(ApprovalBridge.class);

    public enum Decision { PENDING, APPROVED, REJECTED }

    public record PendingApproval(
        String id,
        String title,
        String description,
        String riskLevel,
        long createdAt
    ) {}

    private final Map<String, PendingApproval> registry = new ConcurrentHashMap<>();
    private final Map<String, Decision> decisions = new ConcurrentHashMap<>();

    /**
     * Record an approval request. Returns a generated id. The side-effect
     * described by {@code title}/{@code description} is NOT executed
     * here — the caller must wait for {@link #checkDecision} to return
     * APPROVED before proceeding.
     */
    public String request(String title, String description, String riskLevel) {
        String id = "jarvis-appr-" + UUID.randomUUID();
        registry.put(id, new PendingApproval(
            id,
            title == null ? "Jarvis action" : title,
            description == null ? "" : description,
            riskLevel == null ? "medium" : riskLevel,
            System.currentTimeMillis()
        ));
        decisions.put(id, Decision.PENDING);
        log.info("Jarvis approval request {} recorded ({} / risk={})", id, title, riskLevel);
        return id;
    }

    /**
     * Resolve a pending approval. No-op if the id is unknown.
     */
    public void resolve(String approvalId, String decisionStr) {
        if (approvalId == null || approvalId.isBlank()) return;
        Decision d = parseDecision(decisionStr);
        decisions.put(approvalId, d);
        log.info("Jarvis approval {} resolved → {}", approvalId, d);
    }

    public Decision checkDecision(String approvalId) {
        if (approvalId == null) return Decision.PENDING;
        return decisions.getOrDefault(approvalId, Decision.PENDING);
    }

    public PendingApproval get(String approvalId) {
        return registry.get(approvalId);
    }

    private static Decision parseDecision(String s) {
        if (s == null) return Decision.REJECTED;
        return switch (s.toLowerCase()) {
            case "approve", "approved" -> Decision.APPROVED;
            case "reject", "rejected" -> Decision.REJECTED;
            default -> Decision.REJECTED;
        };
    }
}
