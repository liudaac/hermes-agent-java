package com.nousresearch.hermes.business.approval;

/**
 * S2-1 #3: 审批存储抽象接口。
 *
 * <p>多实例部署时，审批可能在节点 A 发起，回调到节点 B。
 * 此接口抽象审批状态存储，让回调能在任何节点处理。</p>
 *
 * <p>工作流：</p>
 * <ol>
 *   <li>节点 A 发起审批 → {@link #storePending} 存入审批状态（含 nodeId）</li>
 *   <li>节点 A 本地 CountDownLatch 等待</li>
 *   <li>审批回调到任意节点 → {@link #resolve} 获取审批的 nodeId</li>
 *   <li>如果是本地 → 直接 {@link #publishResult} 通知等待线程</li>
 *   <li>如果是远程 → 通过 Pub/Sub 通知目标节点</li>
 * </ol>
 */
public interface ApprovalStore {

    /**
     * 存储待审批状态。
     *
     * @param approvalId 审批 ID
     * @param nodeId 发起审批的节点 ID
     * @param approvalType 审批类型
     * @param operation 审批操作
     */
    void storePending(String approvalId, String nodeId, String approvalType, String operation);

    /**
     * 解析审批的归属节点。
     *
     * @param approvalId 审批 ID
     * @return 节点 ID，如果审批不存在返回 null
     */
    String resolveNode(String approvalId);

    /**
     * 发布审批结果（通知等待的节点）。
     *
     * @param approvalId 审批 ID
     * @param approved 是否批准
     * @param reason 原因（可选）
     */
    void publishResult(String approvalId, boolean approved, String reason);

    /**
     * 订阅审批结果（本节点发起的审批等待回调）。
     *
     * @param approvalId 审批 ID
     * @param callback 结果回调
     */
    void subscribe(String approvalId, ApprovalResultCallback callback);

    /**
     * 取消订阅。
     *
     * @param approvalId 审批 ID
     */
    void unsubscribe(String approvalId);

    /**
     * 标记审批已完成（清理状态）。
     *
     * @param approvalId 审批 ID
     */
    void complete(String approvalId);

    /**
     * 获取待审批数量。
     */
    int pendingCount();

    /**
     * 审批结果回调。
     */
    @FunctionalInterface
    interface ApprovalResultCallback {
        void onResult(boolean approved, String reason);
    }
}
