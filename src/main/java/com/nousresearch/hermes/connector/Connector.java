package com.nousresearch.hermes.connector;

import java.util.List;
import java.util.Map;

/**
 * 外部系统连接器接口 — 为 Agent 工具提供统一的第三方系统集成能力。
 *
 * <p>典型集成对象：
 * <ul>
 *   <li>电商平台：淘宝、京东、拼多多、抖音</li>
 *   <li>ERP/WMS：旺店通、聚水潭、管易云</li>
 *   <li>物流：菜鸟、顺丰、京东物流</li>
 *   <li>支付：支付宝、微信支付</li>
 * </ul>
 * <p>每个连接器注册后，其支持的操作自动暴露为 Agent 可用的 Tool。</p>
 */
public interface Connector {

    /** 唯一标识，如 "taobao"、"cainiao"、"jushuitan" */
    String getName();

    /** 前端展示用的人类可读标签 */
    String getLabel();

    /** 简短描述，说明该连接器对接的是什么系统 */
    String getDescription();

    /** 测试连接是否配置正确且可达 */
    boolean testConnection();

    /**
     * 执行指定操作。
     * @param operation 操作名称（来自 {@link #getSupportedOperations()}）
     * @param params    操作特定参数
     * @return 操作结果
     */
    Map<String, Object> execute(String operation, Map<String, Object> params);

    /** 列出该连接器支持的所有操作 */
    List<ConnectorOperation> getSupportedOperations();

    /** 获取配置 schema（用于前端动态表单生成） */
    Map<String, Object> getConfigSchema();

    /** 更新连接器配置（如 API Key、Endpoint 等） */
    void configure(Map<String, Object> config);

    /** 当前健康状态检查 */
    boolean isHealthy();

    /**
     * 连接器操作定义 — 描述一个操作的名称、参数结构、返回结构。
     * 用于自动生成 Agent Tool 定义和前端表单。
     */
    record ConnectorOperation(
        String name,        // 操作标识
        String label,       // 展示标签
        String description, // 操作说明
        Map<String, Object> parameterSchema, // 参数 JSON Schema
        Map<String, Object> responseSchema   // 返回 JSON Schema
    ) {}
}
