package com.nousresearch.hermes.blueprint;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI-driven quick team builder — turns a natural language description into a
 * {@link TeamBlueprintRecord} draft via LLM, then lets the user confirm and publish.
 *
 * <p>Usage:</p>
 * <ol>
 *   <li>Call {@link #generateDraft(String, String)} with user description</li>
 *   <li>Show draft to user; collect answers to at most 3 clarifying questions</li>
 *   <li>Call {@link #refineDraft(String, String, String, List)} with answers</li>
 *   <li>User confirms → call {@link #publishDraft(TeamBlueprintService, String, Draft)}</li>
 * </ol>
 */
public class QuickTeamBuilderService {

    private static final Logger logger = LoggerFactory.getLogger(QuickTeamBuilderService.class);

    private final ModelClient modelClient;

    public QuickTeamBuilderService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    /**
     * Generate a team blueprint draft from a natural language description.
     *
     * @param workspaceId the target workspace (for context only)
     * @param description what the user wants, e.g. "帮我做一个处理售后退货的客服团队"
     * @return draft + clarifying questions
     */
    public DraftResult generateDraft(String workspaceId, String description) {
        List<ModelMessage> messages = buildPrompt(description, null, null);
        String json = callLLM(messages);
        return parseDraft(json);
    }

    /**
     * Refine an existing draft using clarifying answers.
     *
     * @param workspaceId   target workspace
     * @param description   original user description
     * @param previousDraft the previous draft JSON (from generateDraft)
     * @param answers       user answers to clarifying questions
     * @return refined draft + new clarifying questions (if any)
     */
    public DraftResult refineDraft(String workspaceId, String description,
                                    String previousDraft, List<String> answers) {
        List<ModelMessage> messages = buildPrompt(description, previousDraft, answers);
        String json = callLLM(messages);
        return parseDraft(json);
    }

    /**
     * Publish a confirmed draft into the workspace.
     *
     * @param service     the blueprint service to persist with
     * @param workspaceId target workspace
     * @param draft       the confirmed draft
     * @return created {@link TeamBlueprintRecord}
     */
    public TeamBlueprintRecord publishDraft(TeamBlueprintService service,
                                             String workspaceId, DraftResult draft) {
        String teamId = draft.teamId != null && !draft.teamId.isBlank()
            ? draft.teamId
            : "team-" + UUID.randomUUID().toString().substring(0, 8);

        List<AgentBlueprintRecord> agents = draft.agents != null ? draft.agents : List.of();

        TeamBlueprintRecord record = service.createTeamBlueprint(
            workspaceId,
            teamId,
            draft.teamName != null ? draft.teamName : teamId,
            draft.description,
            draft.scenario,
            draft.scenarioId,
            agents,
            null, // promptAssetRefs
            draft.operatingManual,
            null  // metadata
        );

        // Auto-activate version 1 for immediate use
        service.activateVersion(workspaceId, teamId, 1);

        logger.info("Published quick team {}/{} with {} agents", workspaceId, teamId, agents.size());
        return record;
    }

    // ------------------------------------------------------------------
    // LLM prompt construction
    // ------------------------------------------------------------------

    private List<ModelMessage> buildPrompt(String description, String previousDraft, List<String> answers) {
        StringBuilder system = new StringBuilder();
        system.append("你是一个专业的业务智能体团队设计助手。你的任务是根据用户的自然语言描述，生成一个结构化的团队蓝图草案。\n\n");
        system.append("输出必须是 JSON 格式，包含以下字段：\n");
        system.append("  teamId: 简短英文ID（小写，用-连接）\n");
        system.append("  teamName: 团队显示名称\n");
        system.append("  description: 团队描述（一句话）\n");
        system.append("  scenario: 业务场景名称\n");
        system.append("  scenarioId: 场景英文ID\n");
        system.append("  operatingManual: 团队操作手册（给Agent的instructions）\n");
        system.append("  agents: Agent列表，每个Agent包含 agentId, displayName, responsibility, allowedTools, approvalRules\n");
        system.append("  approvalThreshold: 审批阈值描述\n");
        system.append("  tone: 回复口吻风格\n");
        system.append("  suggestedConnectors: 推荐接入的外部系统\n");
        system.append("  questions: 最多3个需要向用户澄清的关键问题\n\n");
        system.append("约束：\n");
        system.append("- 至少2个Agent，最多5个\n");
        system.append("- 每个Agent必须有明确的 responsibility\n");
        system.append("- 必须包含至少一条 approvalRules（如金额阈值、敏感操作等）\n");
        system.append("- allowedTools 限定为具体工具名，如 policy.search, oms.query, email.send\n");
        system.append("- 如果用户描述不清晰，questions 数组应包含需要澄清的问题\n");
        system.append("- 问题要具体，帮助用户完善团队配置\n\n");
        system.append("行业上下文：电商物流B端用户。\n");

        StringBuilder user = new StringBuilder();
        user.append("用户需求：").append(description).append("\n");
        if (previousDraft != null && !previousDraft.isBlank()) {
            user.append("\n上一轮草案：").append(previousDraft).append("\n");
            if (answers != null && !answers.isEmpty()) {
                user.append("\n用户回答：\n");
                for (int i = 0; i < answers.size(); i++) {
                    user.append(i + 1).append(". ").append(answers.get(i)).append("\n");
                }
            }
            user.append("\n请基于以上信息优化草案。如果还有不确定的地方，继续提问（最多3个）。\n");
        }

        return List.of(
            ModelMessage.system(system.toString()),
            ModelMessage.user(user.toString())
        );
    }

    private String callLLM(List<ModelMessage> messages) {
        var response = modelClient.chatCompletion(messages, null, false);
        if (response == null || !response.isSuccess()) {
            throw new RuntimeException("LLM returned empty or failed response");
        }
        String content = response.getContent();
        logger.debug("QuickTeamBuilder LLM response: {}", content);
        return content;
    }

    private DraftResult parseDraft(String json) {
        // Try to extract JSON from markdown code block
        String raw = json.trim();
        if (raw.startsWith("```")) {
            int start = raw.indexOf("{");
            int end = raw.lastIndexOf("}");
            if (start >= 0 && end > start) {
                raw = raw.substring(start, end + 1);
            }
        }

        JSONObject obj = JSON.parseObject(raw);
        DraftResult result = new DraftResult();
        result.teamId = obj.getString("teamId");
        result.teamName = obj.getString("teamName");
        result.description = obj.getString("description");
        result.scenario = obj.getString("scenario");
        result.scenarioId = obj.getString("scenarioId");
        result.operatingManual = obj.getString("operatingManual");
        result.approvalThreshold = obj.getString("approvalThreshold");
        result.tone = obj.getString("tone");

        var agentsArr = obj.getJSONArray("agents");
        if (agentsArr != null) {
            result.agents = new ArrayList<>();
            for (int i = 0; i < agentsArr.size(); i++) {
                var a = agentsArr.getJSONObject(i);
                AgentBlueprintRecord agent = new AgentBlueprintRecord()
                    .setAgentId(a.getString("agentId"))
                    .setDisplayName(a.getString("displayName"))
                    .setResponsibility(a.getString("responsibility"));

                var tools = a.getJSONArray("allowedTools");
                if (tools != null) {
                    for (int j = 0; j < tools.size(); j++) {
                        agent.getAllowedTools().add(tools.getString(j));
                    }
                }
                var rules = a.getJSONArray("approvalRules");
                if (rules != null) {
                    for (int j = 0; j < rules.size(); j++) {
                        agent.getApprovalRules().add(rules.getString(j));
                    }
                }
                var skills = a.getJSONArray("knowledgeRefs");
                if (skills != null) {
                    for (int j = 0; j < skills.size(); j++) {
                        agent.getKnowledgeRefs().add(skills.getString(j));
                    }
                }
                result.agents.add(agent);
            }
        }

        var connArr = obj.getJSONArray("suggestedConnectors");
        if (connArr != null) {
            result.suggestedConnectors = new ArrayList<>();
            for (int i = 0; i < connArr.size(); i++) {
                result.suggestedConnectors.add(connArr.getString(i));
            }
        }

        var qArr = obj.getJSONArray("questions");
        if (qArr != null) {
            result.questions = new ArrayList<>();
            for (int i = 0; i < qArr.size() && i < 3; i++) {
                result.questions.add(qArr.getString(i));
            }
        }

        result.rawJson = raw;
        return result;
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public static class DraftResult {
        public String teamId;
        public String teamName;
        public String description;
        public String scenario;
        public String scenarioId;
        public String operatingManual;
        public List<AgentBlueprintRecord> agents;
        public String approvalThreshold;
        public String tone;
        public List<String> suggestedConnectors;
        public List<String> questions;
        public String rawJson;
    }
}
