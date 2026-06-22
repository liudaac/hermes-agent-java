package com.nousresearch.hermes.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 连接器注册中心 — 管理外部系统连接器的生命周期、健康检查和 Agent Tool 暴露。
 *
 * <p>核心职责：
 * <ul>
 *   <li>注册/注销连接器</li>
 *   <li>定时健康检查（testConnection）</li>
 *   <li>将连接器操作自动转换为 Agent Tool 定义</li>
 * </ul>
 */
public class ConnectorRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ConnectorRegistry.class);

    /** name → Connector 实例映射 */
    private final ConcurrentHashMap<String, Connector> connectors = new ConcurrentHashMap<>();
    /** name → 健康状态映射，用于前端健康看板 */
    private final ConcurrentHashMap<String, ConnectorHealth> healthStatus = new ConcurrentHashMap<>();

    /** 注册连接器，初始化时默认标记为健康 */
    public void register(Connector connector) {
        connectors.put(connector.getName(), connector);
        healthStatus.put(connector.getName(), new ConnectorHealth(connector.getName(), true, null, System.currentTimeMillis()));
        logger.info("Registered connector: {} ({})", connector.getName(), connector.getLabel());
    }

    /** 按名称获取连接器 */
    public Optional<Connector> get(String name) {
        return Optional.ofNullable(connectors.get(name));
    }

    /** 列出所有已注册的连接器 */
    public List<Connector> listAll() {
        return new ArrayList<>(connectors.values());
    }

    /** 按类别前缀筛选连接器，如 "ecommerce" 可匹配 "taobao"、"jd" 等 */
    public List<Connector> listByCategory(String category) {
        return connectors.values().stream()
            .filter(c -> c.getName().startsWith(category + ".") || c.getName().equals(category))
            .collect(Collectors.toList());
    }

    /**
     * 在指定连接器上执行操作。
     * @throws IllegalArgumentException 连接器不存在
     * @throws IllegalStateException    连接器不健康
     */
    public Map<String, Object> execute(String connectorName, String operation, Map<String, Object> params) {
        Connector connector = connectors.get(connectorName);
        if (connector == null) {
            throw new IllegalArgumentException("Connector not found: " + connectorName);
        }
        if (!connector.isHealthy()) {
            throw new IllegalStateException("Connector " + connectorName + " is not healthy");
        }
        return connector.execute(operation, params);
    }

    /** 对所有连接器执行健康检查，更新 healthStatus */
    public void runHealthChecks() {
        for (Connector connector : connectors.values()) {
            try {
                boolean healthy = connector.testConnection();
                healthStatus.put(connector.getName(),
                    new ConnectorHealth(connector.getName(), healthy, null, System.currentTimeMillis()));
            } catch (Exception e) {
                healthStatus.put(connector.getName(),
                    new ConnectorHealth(connector.getName(), false, e.getMessage(), System.currentTimeMillis()));
                logger.warn("Health check failed for connector {}: {}", connector.getName(), e.getMessage());
            }
        }
    }

    /** 获取健康状态摘要，供前端看板展示 */
    public Map<String, Object> getHealthSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", connectors.size());
        summary.put("healthy", healthStatus.values().stream().filter(ConnectorHealth::healthy).count());
        summary.put("unhealthy", healthStatus.values().stream().filter(h -> !h.healthy()).count());
        summary.put("details", new ArrayList<>(healthStatus.values()));
        return summary;
    }

    /**
     * 将所有连接器操作转换为 Agent Tool 定义列表。
     * 每个操作生成一个 tool entry，Agent 可直接调用。
     */
    public List<Map<String, Object>> toToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Connector connector : connectors.values()) {
            for (Connector.ConnectorOperation op : connector.getSupportedOperations()) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", connector.getName() + "_" + op.name());
                tool.put("connector", connector.getName());
                tool.put("operation", op.name());
                tool.put("description", "[" + connector.getLabel() + "] " + op.description());
                tool.put("parameters", op.parameterSchema());
                tools.add(tool);
            }
        }
        return tools;
    }

    /** 健康状态记录 */
    public record ConnectorHealth(String connectorName, boolean healthy, String error, long checkedAt) {}
}
