package com.nousresearch.hermes.memory;

import java.util.List;
import java.util.Map;

/**
 * S4-3: 外部记忆 Provider SPI。
 *
 * <p>对齐原版 plugins/memory/ 的 9 家外部 memory provider。
 * 首批适配：mem0（HTTP API）。</p>
 *
 * <p>工作流：</p>
 * <ol>
 *   <li>{@link #store} — 存储记忆（agent 学到的东西）</li>
 *   <li>{@link #search} — 语义搜索记忆</li>
 *   <li>{@link #retrieve} — 按 ID 获取</li>
 *   <li>{@link #delete} — 删除记忆</li>
 * </ol>
 */
public interface ExternalMemoryProvider {

    /**
     * Provider 名称（如 "mem0", "honcho"）。
     */
    String name();

    /**
     * 初始化（建立连接等）。
     */
    void initialize(Map<String, String> config);

    /**
     * 存储一条记忆。
     *
     * @param tenantId 租户 ID
     * @param agentId agent ID
     * @param content 记忆内容
     * @param metadata 元数据（可选）
     * @return 记忆 ID
     */
    String store(String tenantId, String agentId, String content, Map<String, String> metadata);

    /**
     * 语义搜索记忆。
     *
     * @param tenantId 租户 ID
     * @param query 查询文本
     * @param limit 最大返回数
     * @return 搜索结果列表
     */
    List<MemoryRecord> search(String tenantId, String query, int limit);

    /**
     * 按 ID 获取记忆。
     */
    MemoryRecord retrieve(String tenantId, String memoryId);

    /**
     * 删除记忆。
     */
    boolean delete(String tenantId, String memoryId);

    /**
     * 列出所有记忆（分页）。
     */
    List<MemoryRecord> list(String tenantId, int offset, int limit);

    /**
     * 健康检查。
     */
    boolean isAvailable();

    /**
     * 记忆记录。
     */
    record MemoryRecord(
        String id,
        String agentId,
        String content,
        Map<String, String> metadata,
        long createdAt,
        double score
    ) {
        public MemoryRecord withScore(double s) {
            return new MemoryRecord(id, agentId, content, metadata, createdAt, s);
        }
    }
}
