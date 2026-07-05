package com.nousresearch.hermes.memory;

import java.util.*;

/**
 * S4-3: 本地文件记忆 Provider — 使用已有的 MemoryManager 基础设施。
 *
 * <p>不依赖外部服务，直接读写 MEMORY.md / USER.md。
 * 作为默认 provider 和 fallback 使用。</p>
 */
public class LocalFileMemoryProvider implements ExternalMemoryProvider {
    private final MemoryManager memoryManager;

    public LocalFileMemoryProvider(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @Override
    public String name() { return "local-file"; }

    @Override
    public void initialize(Map<String, String> config) {
        // 无需初始化，MemoryManager 已就绪
    }

    @Override
    public String store(String tenantId, String agentId, String content, Map<String, String> metadata) {
        String id = UUID.randomUUID().toString();
        memoryManager.addMemory(content);
        return id;
    }

    @Override
    public List<MemoryRecord> search(String tenantId, String query, int limit) {
        List<String> results = memoryManager.search(query, limit);
        List<MemoryRecord> records = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            records.add(new MemoryRecord(
                "local-" + i, "default", results.get(i),
                Map.of(), System.currentTimeMillis(), 1.0 - (i * 0.1)
            ));
        }
        return records;
    }

    @Override
    public MemoryRecord retrieve(String tenantId, String memoryId) {
        // 本地文件不支持按 ID 获取，返回 null
        return null;
    }

    @Override
    public boolean delete(String tenantId, String memoryId) {
        // 本地文件不支持按 ID 删除
        return false;
    }

    @Override
    public List<MemoryRecord> list(String tenantId, int offset, int limit) {
        // 用空查询获取所有条目（search 返回最相关的结果）
        List<String> all = memoryManager.getByCategory("memory", offset + limit);
        List<MemoryRecord> records = new ArrayList<>();
        for (int i = offset; i < Math.min(offset + limit, all.size()); i++) {
            records.add(new MemoryRecord(
                "local-" + i, "default", all.get(i),
                Map.of(), System.currentTimeMillis(), 0
            ));
        }
        return records;
    }

    @Override
    public boolean isAvailable() {
        return memoryManager != null;
    }
}
