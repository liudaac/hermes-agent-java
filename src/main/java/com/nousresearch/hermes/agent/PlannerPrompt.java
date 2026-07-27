package com.nousresearch.hermes.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Planner prompt 构建 + 输出解析。
 *
 * <p>planner 是 ModelChain 的第一步，拿到用户原始请求和可用工具列表，
 * 输出 {@link ExecutionPlan} JSON。</p>
 */
public final class PlannerPrompt {

    private static final Logger logger = LoggerFactory.getLogger(PlannerPrompt.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlannerPrompt() {}

    /**
     * 构建 planner 的 system prompt。
     *
     * <p>包含：</p>
     * <ol>
     *   <li>角色定位（你是任务规划器）</li>
     *   <li>输出格式要求（严格 JSON，不要 markdown 代码块）</li>
     *   <li>JSON schema 说明</li>
     *   <li>可用工具列表（名字 + 描述）</li>
     * </ol>
     */
    public static String buildSystemPrompt(List<com.nousresearch.hermes.model.ToolDefinition> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是任务规划器。分析用户请求，拆解为有序执行步骤。\n\n");

        sb.append("输出格式（严格 JSON，不要 markdown 代码块，不要任何前后缀文字）：\n");
        sb.append("{\n");
        sb.append("  \"goal\": \"任务目标复述\",\n");
        sb.append("  \"steps\": [\n");
        sb.append("    {\n");
        sb.append("      \"id\": \"s1\",\n");
        sb.append("      \"action\": \"做什么（自然语言描述）\",\n");
        sb.append("      \"tool\": \"工具名\",\n");
        sb.append("      \"dependsOn\": [],\n");
        sb.append("      \"expectedOutput\": \"这步完成后应该产出什么\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"successCriteria\": [\"完成标准1\", \"完成标准2\"],\n");
        sb.append("  \"notes\": \"风险、约束等备注\"\n");
        sb.append("}\n\n");

        sb.append("规则：\n");
        sb.append("- steps 可以为空数组（不需要拆步时）\n");
        sb.append("- tool 必须从下方可用工具列表中选择，纯推理步骤用空字符串\n");
        sb.append("- dependsOn 引用前置 step 的 id，无依赖用空数组\n");
        sb.append("- successCriteria 要可验证，reviewer 会逐条检查\n\n");

        // 可用工具列表
        if (tools != null && !tools.isEmpty()) {
            sb.append("可用工具：\n");
            for (var tool : tools) {
                sb.append("- ").append(tool.getName() != null ? tool.getName() : "unknown");
                if (tool.getDescription() != null && !tool.getDescription().isBlank()) {
                    sb.append(": ").append(tool.getDescription());
                }
                sb.append("\n");
            }
        } else {
            sb.append("可用工具：无（纯推理模式）\n");
        }

        return sb.toString();
    }

    /**
     * 构建 reviewer 的 system prompt。
     *
     * <p>reviewer 拿 ExecutionPlan + 每步实际输出，逐条检查 successCriteria，
     * 输出 {@link ExecutionPlan.ReviewResult} JSON。</p>
     */
    public static String buildReviewerPrompt(ExecutionPlan plan,
                                              List<String> stepOutputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是任务评审器。检查执行结果是否符合计划。\n\n");

        sb.append("输出格式（严格 JSON，不要 markdown 代码块）：\n");
        sb.append("{\n");
        sb.append("  \"approved\": true/false,\n");
        sb.append("  \"stepReviews\": [\n");
        sb.append("    {\"stepId\": \"s1\", \"passed\": true/false, \"feedback\": \"说明\"}\n");
        sb.append("  ],\n");
        sb.append("  \"summary\": \"整体评审总结\",\n");
        sb.append("  \"maxRetries\": 1\n");
        sb.append("}\n\n");

        sb.append("规则：\n");
        sb.append("- 逐条检查 successCriteria\n");
        sb.append("- 不通过的 step 标 passed=false 并说明原因\n");
        sb.append("- maxRetries 表示允许重跑的最大次数\n\n");

        // 原始计划
        sb.append("原始计划：\n");
        try {
            sb.append(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
        } catch (Exception e) {
            sb.append("[序列化失败: ").append(e.getMessage()).append("]");
        }

        // 每步实际输出
        sb.append("\n\n执行结果：\n");
        if (plan.steps() != null) {
            for (int i = 0; i < plan.steps().size(); i++) {
                String stepId = plan.steps().get(i).id();
                String output = i < stepOutputs.size() ? stepOutputs.get(i) : "[未执行]";
                sb.append("--- ").append(stepId).append(" ---\n");
                sb.append(output).append("\n\n");
            }
        }

        return sb.toString();
    }

    /**
     * 从 LLM 输出解析 ExecutionPlan。
     *
     * <p>容错策略：</p>
     * <ol>
     *   <li>直接 JSON 反序列化</li>
     *   <li>如果失败，尝试提取 ```json ... ``` 代码块内的 JSON</li>
     *   <li>如果仍然失败，返回 passthrough 计划（让 executor 直接处理）</li>
     * </ol>
     */
    public static ExecutionPlan parsePlan(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return ExecutionPlan.passthrough("[空输出]");
        }

        String json = extractJson(llmOutput);
        if (json == null) {
            logger.warn("Failed to extract JSON from planner output, falling back to passthrough");
            return ExecutionPlan.passthrough(llmOutput.substring(0, Math.min(llmOutput.length(), 200)));
        }

        try {
            return MAPPER.readValue(json, ExecutionPlan.class);
        } catch (Exception e) {
            logger.warn("Failed to parse ExecutionPlan JSON: {}", e.getMessage());
            return ExecutionPlan.passthrough(llmOutput.substring(0, Math.min(llmOutput.length(), 200)));
        }
    }

    /**
     * 从 LLM 输出解析 ReviewResult。
     */
    public static ExecutionPlan.ReviewResult parseReview(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return new ExecutionPlan.ReviewResult(true, List.of(), "Empty review, auto-approve", 1);
        }

        String json = extractJson(llmOutput);
        if (json == null) {
            logger.warn("Failed to extract JSON from reviewer output, auto-approving");
            return new ExecutionPlan.ReviewResult(true, List.of(), "Parse failed, auto-approve", 1);
        }

        try {
            return MAPPER.readValue(json, ExecutionPlan.ReviewResult.class);
        } catch (Exception e) {
            logger.warn("Failed to parse ReviewResult JSON: {}", e.getMessage());
            return new ExecutionPlan.ReviewResult(true, List.of(), "Parse failed, auto-approve", 1);
        }
    }

    /**
     * 从 LLM 输出中提取 JSON 对象。
     * 支持：纯 JSON、```json ... ``` 代码块、前后有文字的情况。
     */
    private static String extractJson(String text) {
        // 尝试直接解析
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        // 尝试提取 ```json ... ``` 代码块
        int jsonStart = trimmed.indexOf("```json");
        if (jsonStart >= 0) {
            int contentStart = trimmed.indexOf("\n", jsonStart);
            if (contentStart >= 0) {
                int contentEnd = trimmed.indexOf("```", contentStart);
                if (contentEnd > contentStart) {
                    return trimmed.substring(contentStart + 1, contentEnd).trim();
                }
            }
        }

        // 尝试提取第一个 { 到最后一个 } 的内容
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return null;
    }
}
