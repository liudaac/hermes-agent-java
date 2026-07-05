package com.nousresearch.hermes.tenant.quota;

/**
 * S2-1 #1: 配额存储抽象接口。
 *
 * <p>多实例部署时，配额计数必须共享。此接口抽象存储层，
 * 让 {@link TenantQuotaManager} 不关心是本地内存还是 Redis。</p>
 *
 * <p>所有操作都是原子性的：</p>
 * <ul>
 *   <li>{@link #incrementAndGetDailyRequests} — 原子自增 + 返回新值</li>
 *   <li>{@link #addAndGetDailyTokens} — 原子加 + 返回新值</li>
 *   <li>{@link #decrementDailyRequests} — 原子减（配额超限时回滚）</li>
 * </ul>
 */
public interface QuotaStore {

    /**
     * 原子自增每日请求数并返回新值。
     * @return 自增后的值
     */
    long incrementAndGetDailyRequests();

    /**
     * 原子减少每日请求数（配额超限时回滚）。
     */
    void decrementDailyRequests();

    /**
     * 原子加上每日 token 用量并返回新值。
     * @param tokens 增加的 token 数
     * @return 加完后的新值
     */
    long addAndGetDailyTokens(long tokens);

    /**
     * 原子减少每日 token 用量（配额超限时回滚）。
     * @param tokens 减少的 token 数
     */
    void subtractDailyTokens(long tokens);

    /**
     * 增加活跃 agent 计数。
     */
    void incrementActiveAgents();

    /**
     * 减少活跃 agent 计数。
     */
    void decrementActiveAgents();

    /**
     * 加上存储用量。
     * @param bytes 增加的字节数
     * @return 新值
     */
    long addAndGetStorageUsage(long bytes);

    /**
     * 减去存储用量。
     * @param bytes 减少的字节数
     */
    void subtractStorageUsage(long bytes);

    // ============ Getters ============

    long getDailyRequests();
    long getDailyTokens();
    int getActiveAgents();
    long getStorageUsage();

    /**
     * 检查并重置每日配额（跨日时触发）。
     * @return true 如果发生了重置
     */
    boolean checkAndResetDaily();

    /**
     * 保存使用量（持久化）。
     */
    void saveUsage();

    /**
     * 加载使用量。
     */
    void loadUsage();
}
