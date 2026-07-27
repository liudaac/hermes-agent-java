package com.nousresearch.hermes.agent;

import java.util.List;

/**
 * Planner step 的结构化输出。
 *
 * <p>planner 拿到用户请求 + 可用工具列表，输出这个 JSON。
 * executor 解析 steps[] 逐条执行；reviewer 拿 plan + 每步结果做校验，
 * 不通过的 step 回退重跑（最多 maxRetries 次）。</p>
 *
 * <p>设计决策：</p>
 * <ul>
 *   <li><b>tool 字段给工具名</b>（如 "code", "browser", "file_read"），
 *       planner 需要从可用 ToolDefinition 列表中选择，空字符串表示纯推理</li>
 *   <li><b>reviewer 发现问题回退</b>：标记有问题的 step id，
 *       executor 从该 step 重新执行（携带 reviewer 的反馈）</li>
 *   <li><b>steps 为空时 executor 直接透传</b>：planner 判断不需要拆步，
 *       把原始请求直接交给 executor 当普通单轮对话处理</li>
 * </ul>
 */
public record ExecutionPlan(
        String goal,
        List<PlanStep> steps,
        List<String> successCriteria,
        String notes
) {
    /**
     * 空计划 - planner 判断不需要拆步时返回。
     * executor 拿到空计划时直接透传原始输入给模型。
     */
    public static ExecutionPlan passthrough(String goal) {
        return new ExecutionPlan(goal, List.of(), List.of(), "No decomposition needed");
    }

    public boolean isPassthrough() {
        return steps == null || steps.isEmpty();
    }

    /**
     * 根据 id 查找 step，找不到返回 null。
     */
    public PlanStep findStep(String stepId) {
        if (steps == null) return null;
        return steps.stream()
            .filter(s -> stepId.equals(s.id()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 单个执行步骤。
     *
     * @param id             "s1", "s2"... 用于 dependsOn 引用
     * @param action         做什么（自然语言描述，executor 拿来执行）
     * @param tool           建议用的工具名（如 "code", "browser", "file_read"），
     *                       空字符串表示纯推理，不需要调工具
     * @param dependsOn      依赖哪些前置 step 的 id，空列表表示无依赖
     * @param expectedOutput 这步完成后应该产出什么（reviewer 判断依据）
     */
    public record PlanStep(
            String id,
            String action,
            String tool,
            List<String> dependsOn,
            String expectedOutput
    ) {
        public boolean isToolStep() {
            return tool != null && !tool.isBlank();
        }

        public boolean hasDependencies() {
            return dependsOn != null && !dependsOn.isEmpty();
        }
    }

    /**
     * Reviewer 对执行结果的结构化评审。
     *
     * <p>reviewer 拿 ExecutionPlan + 每步实际输出，逐条检查 successCriteria，
     * 不通过的标记 step id 并给出反馈。executor 根据 retrySteps 回退重跑。</p>
     */
    public record ReviewResult(
            boolean approved,
            List<StepReview> stepReviews,
            String summary,
            int maxRetries
    ) {
        /**
         * 需要重跑的 step id 列表（按顺序）。
         */
        public List<String> retryStepIds() {
            if (stepReviews == null) return List.of();
            return stepReviews.stream()
                .filter(s -> !s.passed())
                .map(StepReview::stepId)
                .toList();
        }

        public boolean needsRetry() {
            return !approved && !retryStepIds().isEmpty();
        }
    }

    /**
     * Reviewer 对单个 step 的评审。
     */
    public record StepReview(
            String stepId,
            boolean passed,
            String feedback
    ) {}
}
