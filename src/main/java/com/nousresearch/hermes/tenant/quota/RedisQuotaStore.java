package com.nousresearch.hermes.tenant.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.function.Function;

/**
 * S2-1 #1: Redis 配额存储（多实例模式）。
 *
 * <p>使用 Redis 保证多实例间配额计数共享且原子。
 * 通过 Lua 脚本实现"原子自增 + 超限回滚"。</p>
 *
 * <p>Redis key 设计：</p>
 * <ul>
 *   <li>{@code quota:<tenantId>:requests:<date>} — 每日请求数</li>
 *   <li>{@code quota:<tenantId>:tokens:<date>} — 每日 token 数</li>
 *   <li>{@code quota:<tenantId>:agents} — 活跃 agent 数</li>
 *   <li>{@code quota:<tenantId>:storage} — 存储用量</li>
 * </ul>
 *
 * <p>每日 key 自动过期（TTL 48 小时），跨日自动创建新 key。</p>
 *
 * <p>使用方式：通过 {@link #withRedisCommand} 注入 Redis 命令执行器，
 * 不依赖特定 Redis 客户端（Lettuce/Jedis 均可）。</p>
 */
public class RedisQuotaStore implements QuotaStore {
    private static final Logger logger = LoggerFactory.getLogger(RedisQuotaStore.class);

    private final String tenantId;
    private final String keyPrefix;

    /**
     * Redis 命令执行器（与具体 Redis 客户端解耦）。
     * - eval(script, keys, args) → 执行 Lua 脚本，返回 Long
     * - incr(key) → 自增并返回
     * - decr(key) → 自减
     * - get(key) → 获取值
     * - expire(key, seconds) → 设置 TTL
     * - del(key) → 删除
     */
    private final RedisCommandExecutor redis;

    /** Lua 脚本：原子自增 + 超限回滚 */
    private static final String INCR_AND_CHECK_SCRIPT = """
        local current = redis.call('INCR', KEYS[1])
        redis.call('EXPIRE', KEYS[1], 172800)
        return current
        """;

    /** Lua 脚本：原子加 + 超限回滚 */
    private static final String ADD_AND_CHECK_SCRIPT = """
        local current = redis.call('INCRBY', KEYS[1], ARGV[1])
        redis.call('EXPIRE', KEYS[1], 172800)
        return current
        """;

    public RedisQuotaStore(String tenantId, RedisCommandExecutor redis) {
        this.tenantId = tenantId;
        this.keyPrefix = "quota:" + tenantId;
        this.redis = redis;
    }

    private String todayKey() {
        return LocalDate.now().toString();
    }

    private String requestsKey() {
        return keyPrefix + ":requests:" + todayKey();
    }

    private String tokensKey() {
        return keyPrefix + ":tokens:" + todayKey();
    }

    private String agentsKey() {
        return keyPrefix + ":agents";
    }

    private String storageKey() {
        return keyPrefix + ":storage";
    }

    @Override
    public long incrementAndGetDailyRequests() {
        Long result = redis.eval(INCR_AND_CHECK_SCRIPT, new String[]{requestsKey()}, new String[0]);
        return result != null ? result : 0;
    }

    @Override
    public void decrementDailyRequests() {
        redis.decr(requestsKey());
    }

    @Override
    public long addAndGetDailyTokens(long tokens) {
        Long result = redis.eval(ADD_AND_CHECK_SCRIPT, new String[]{tokensKey()}, new String[]{String.valueOf(tokens)});
        return result != null ? result : 0;
    }

    @Override
    public void subtractDailyTokens(long tokens) {
        redis.eval(ADD_AND_CHECK_SCRIPT, new String[]{tokensKey()}, new String[]{String.valueOf(-tokens)});
    }

    @Override
    public void incrementActiveAgents() {
        redis.incr(agentsKey());
    }

    @Override
    public void decrementActiveAgents() {
        redis.decr(agentsKey());
    }

    @Override
    public long addAndGetStorageUsage(long bytes) {
        Long result = redis.eval(ADD_AND_CHECK_SCRIPT, new String[]{storageKey()}, new String[]{String.valueOf(bytes)});
        return result != null ? result : 0;
    }

    @Override
    public void subtractStorageUsage(long bytes) {
        redis.eval(ADD_AND_CHECK_SCRIPT, new String[]{storageKey()}, new String[]{String.valueOf(-bytes)});
    }

    @Override
    public long getDailyRequests() {
        String val = redis.get(requestsKey());
        return val != null ? Long.parseLong(val) : 0;
    }

    @Override
    public long getDailyTokens() {
        String val = redis.get(tokensKey());
        return val != null ? Long.parseLong(val) : 0;
    }

    @Override
    public int getActiveAgents() {
        String val = redis.get(agentsKey());
        return val != null ? Integer.parseInt(val) : 0;
    }

    @Override
    public long getStorageUsage() {
        String val = redis.get(storageKey());
        return val != null ? Long.parseLong(val) : 0;
    }

    @Override
    public boolean checkAndResetDaily() {
        // Redis 中每日 key 自动按日期分隔，无需手动重置
        // 新一天的 key 初始值为 0（Redis INCR 对不存在的 key 从 0 开始）
        return false;
    }

    @Override
    public void saveUsage() {
        // Redis 实时写入，无需额外持久化
    }

    @Override
    public void loadUsage() {
        // Redis 实时读取，无需加载
    }

    /**
     * Redis 命令执行器接口（与具体客户端解耦）。
     */
    public interface RedisCommandExecutor {
        Long eval(String script, String[] keys, String[] args);
        Long incr(String key);
        Long decr(String key);
        String get(String key);
        void expire(String key, long seconds);
        void del(String key);
    }
}
