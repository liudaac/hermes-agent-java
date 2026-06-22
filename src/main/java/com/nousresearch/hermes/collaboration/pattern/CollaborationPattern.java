package com.nousresearch.hermes.collaboration.pattern;

/**
 * 多智能体协作模式枚举。
 *
 * <p>定义了业务场景中子任务如何在团队成员之间分配和协调。
 * 每种模式可在 Scenario 级别指定，覆盖默认的串行执行。</p>
 */
public enum CollaborationPattern {
    /**
     * 顺序链：A → B → C，每步等待前一步完成。
     * 未指定模式时的默认回退策略。
     * 适用场景：订单处理（验证→支付→发货→通知）
     */
    SEQUENTIAL,

    /**
     * 并行扇出：所有子任务同时执行，全部完成后合并结果。
     * 适用场景：同时查询库存、物流、支付状态
     */
    PARALLEL,

    /**
     * 评审模式：Agent A 生成输出 → Agent B 复核 → 人工确认。
     * 高风险任务的质量门禁。
     * 适用场景：客服回复生成、退款审批
     */
    REVIEW,

    /**
     * 竞争模式：N 个 Agent 独立解决同一任务，择优采用。
     * 由评分函数或评审 Agent 选择最佳结果。
     * 适用场景：方案生成、创意写作
     */
    COMPETITIVE,

    /**
     * 主从模式：主管 Agent 拆解任务，Worker 并行执行，主管聚合结果。
     * 适用场景：复杂报告生成、数据分析
     */
    MASTER_WORKER,

    /**
     * 流水线：数据流经 Agent 链，每个 Agent 转换输出并传递给下一个。
     * 类似顺序模式，但有显式的数据交接语义。
     * 适用场景：数据清洗→特征提取→模型预测→结果格式化
     */
    PIPELINE
}
