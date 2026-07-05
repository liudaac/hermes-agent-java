package com.nousresearch.hermes.business.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S2-1 #3: 本地审批存储（单实例模式）。
 *
 * <p>使用 ConcurrentHashMap 管理待审批状态和回调。
 * 单实例部署时使用，多实例时切换到 RedisApprovalStore。</p>
 */
public class LocalApprovalStore implements ApprovalStore {
    private static final Logger logger = LoggerFactory.getLogger(LocalApprovalStore.class);

    private record PendingEntry(String nodeId, String approvalType, String operation) {}

    private final Map<String, PendingEntry> pending = new ConcurrentHashMap<>();
    private final Map<String, ApprovalResultCallback> callbacks = new ConcurrentHashMap<>();
    private final String localNodeId;

    public LocalApprovalStore(String localNodeId) {
        this.localNodeId = localNodeId;
    }

    public LocalApprovalStore() {
        this("local");
    }

    @Override
    public void storePending(String approvalId, String nodeId, String approvalType, String operation) {
        pending.put(approvalId, new PendingEntry(nodeId, approvalType, operation));
        logger.debug("Stored pending approval: id={}, node={}", approvalId, nodeId);
    }

    @Override
    public String resolveNode(String approvalId) {
        PendingEntry entry = pending.get(approvalId);
        return entry != null ? entry.nodeId() : null;
    }

    @Override
    public void publishResult(String approvalId, boolean approved, String reason) {
        ApprovalResultCallback callback = callbacks.get(approvalId);
        if (callback != null) {
            callback.onResult(approved, reason);
            logger.debug("Published approval result locally: id={}, approved={}", approvalId, approved);
        } else {
            logger.warn("No callback for approval: {}", approvalId);
        }
    }

    @Override
    public void subscribe(String approvalId, ApprovalResultCallback callback) {
        callbacks.put(approvalId, callback);
        logger.debug("Subscribed to approval result: id={}", approvalId);
    }

    @Override
    public void unsubscribe(String approvalId) {
        callbacks.remove(approvalId);
    }

    @Override
    public void complete(String approvalId) {
        pending.remove(approvalId);
        callbacks.remove(approvalId);
        logger.debug("Completed approval: id={}", approvalId);
    }

    @Override
    public int pendingCount() {
        return pending.size();
    }
}
