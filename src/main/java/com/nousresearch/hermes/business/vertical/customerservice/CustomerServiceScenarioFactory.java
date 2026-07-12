package com.nousresearch.hermes.business.vertical.customerservice;

import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.collaboration.pattern.CollaborationPattern;
import com.nousresearch.hermes.scenario.ScenarioRecord;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 客服垂直场景工厂 - 预置标准客服场景（非电商专用）。
 *
 * <p>提供 3 套开箱即用的场景：
 * <ul>
 *   <li>工单处理（Ticket Processing）- 接收 -> 分类 -> 处理 -> 回访</li>
 *   <li>投诉升级（Complaint Escalation）- 登记 -> 调查 -> 方案 -> 补偿 -> 关闭</li>
 *   <li>智能问答（Smart Q&A）- 意图识别 -> 知识检索 -> 回答 -> 评价</li>
 * </ul>
 */
public class CustomerServiceScenarioFactory {
    private static final Logger logger = LoggerFactory.getLogger(CustomerServiceScenarioFactory.class);

    private final WorkspaceService workspaceService;
    private final ScenarioService scenarioService;
    private final TeamBlueprintService teamBlueprintService;

    public CustomerServiceScenarioFactory(WorkspaceService workspaceService,
                                           ScenarioService scenarioService,
                                           TeamBlueprintService teamBlueprintService) {
        this.workspaceService = workspaceService;
        this.scenarioService = scenarioService;
        this.teamBlueprintService = teamBlueprintService;
    }

    /**
     * 工单处理场景：接收 -> 分类 -> 处理 -> 回访
     */
    public VerticalScenarioSetup createTicketProcessingScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        String teamId = "ticket-processing-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Ticket Processing Team",
                "Receive, classify, resolve, and follow up on customer support tickets",
                "Ticket Processing", null,
                List.of(
                    agent("ticket-receiver", "Ticket Receiver", "Receive tickets from email, chat, phone; acknowledge receipt; log initial info"),
                    agent("ticket-classifier", "Ticket Classifier", "Classify by category, priority, and product area; route to appropriate handler"),
                    agent("ticket-resolver", "Ticket Resolver", "Investigate issue, apply solution or workaround, communicate progress to customer"),
                    agent("follow-up-agent", "Follow-up Agent", "Verify resolution with customer, collect satisfaction score, close ticket, update knowledge base")
                ),
                List.of("prompt://ticket-processing-standard"),
                getTicketProcessingManual(),
                Map.of("vertical", "customer_service", "domain", "ticket_management")
            );
            logger.info("Created ticket processing team for workspace {}", workspaceId);
        }

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "ticket-processing",
            "Ticket Processing", "End-to-end ticket processing from intake to follow-up",
            teamId,
            List.of("Ticket acknowledged within 5min", "Classification within 10min", "Resolution or escalation within SLA", "Customer satisfaction collected"),
            List.of("always"),
            Map.of("sla", "ticket_processing", "vertical", "customer_service"),
            CollaborationPattern.SEQUENTIAL,
            "ticket_processing"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "ticket-processing");
    }

    /**
     * 投诉升级场景：登记 -> 调查 -> 方案 -> 补偿 -> 关闭
     */
    public VerticalScenarioSetup createComplaintEscalationScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        String teamId = "complaint-escalation-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Complaint Escalation Team",
                "Handle escalated complaints with investigation, resolution, and compensation",
                "Complaint Escalation", null,
                List.of(
                    agent("complaint-registrar", "Complaint Registrar", "Register complaint details, severity, and customer history; assign case number"),
                    agent("investigator", "Investigator", "Investigate complaint root cause, gather evidence, interview involved parties"),
                    agent("resolution-designer", "Resolution Designer", "Design resolution plan: apology, corrective action, compensation options"),
                    agent("compensation-approver", "Compensation Approver", "Review and approve compensation within authority; escalate beyond threshold"),
                    agent("closure-agent", "Closure Agent", "Communicate resolution to customer, confirm satisfaction, close case, file lessons learned")
                ),
                List.of("prompt://complaint-escalation-standard"),
                getComplaintEscalationManual(),
                Map.of("vertical", "customer_service", "domain", "complaint_management")
            );
            logger.info("Created complaint escalation team for workspace {}", workspaceId);
        }

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "complaint-escalation",
            "Complaint Escalation", "Handle escalated complaints from registration to closure",
            teamId,
            List.of("Complaint registered within 1h", "Investigation within 24h", "Resolution plan within 48h", "Customer confirmation collected"),
            List.of("high-risk", "approval-required"),
            Map.of("sla", "complaint_escalation", "vertical", "customer_service"),
            CollaborationPattern.SEQUENTIAL,
            "complaint_escalation"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "complaint-escalation");
    }

    /**
     * 智能问答场景：意图识别 -> 知识检索 -> 回答 -> 评价
     */
    public VerticalScenarioSetup createSmartQAScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        String teamId = "smart-qa-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Smart Q&A Team",
                "AI-powered question answering with knowledge retrieval and quality scoring",
                "Smart Q&A", null,
                List.of(
                    agent("intent-recognizer", "Intent Recognizer", "Recognize user intent, extract entities, determine question type and complexity"),
                    agent("knowledge-retriever", "Knowledge Retriever", "Search knowledge base, FAQs, and documentation; rank results by relevance"),
                    agent("answer-composer", "Answer Composer", "Compose clear, accurate answer from retrieved knowledge; cite sources; suggest related topics"),
                    agent("quality-scorer", "Quality Scorer", "Score answer quality, collect user feedback, flag for knowledge gap if answer is unsatisfactory")
                ),
                List.of("prompt://smart-qa-standard"),
                getSmartQAManual(),
                Map.of("vertical", "customer_service", "domain", "smart_qa")
            );
            logger.info("Created smart QA team for workspace {}", workspaceId);
        }

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "smart-qa",
            "Smart Q&A", "AI-powered question answering with knowledge retrieval and quality scoring",
            teamId,
            List.of("Intent recognized within 2s", "Knowledge retrieved", "Answer composed within 10s", "Quality score collected"),
            List.of("always"),
            Map.of("sla", "smart_qa", "vertical", "customer_service"),
            CollaborationPattern.SEQUENTIAL,
            "smart_qa"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "smart-qa");
    }

    // ─── Helpers ─────────────────────────────────────────────

    private AgentBlueprintRecord agent(String id, String name, String responsibility) {
        return new AgentBlueprintRecord()
            .setAgentId(id)
            .setDisplayName(name)
            .setResponsibility(responsibility)
            .setAllowedTools(List.of("tenant_bus", "memory", "web_search"))
            .setAllowedSkills(List.of());
    }

    private String getTicketProcessingManual() {
        return """
            # Ticket Processing Operating Manual
            1. Acknowledge ticket within 5 minutes
            2. Classify: category, priority (P0-P3), product area
            3. Route to appropriate resolver or team
            4. Investigate and resolve or escalate within SLA:
               - P0: 1h, P1: 4h, P2: 24h, P3: 72h
            5. Communicate progress to customer at regular intervals
            6. Verify resolution with customer before closing
            7. Collect satisfaction score (CSAT)
            8. Update knowledge base with new solutions
            """;
    }

    private String getComplaintEscalationManual() {
        return """
            # Complaint Escalation Operating Manual
            1. Register complaint within 1 hour: details, severity, customer history
            2. Assign case number and acknowledge to customer
            3. Investigate within 24 hours: root cause, evidence, involved parties
            4. Design resolution within 48 hours: apology, corrective action, compensation
            5. Review compensation against policy thresholds
            6. Escalate to management if compensation exceeds authority
            7. Communicate resolution to customer
            8. Confirm satisfaction and close case
            9. File lessons learned and update prevention measures
            """;
    }

    private String getSmartQAManual() {
        return """
            # Smart Q&A Operating Manual
            1. Recognize user intent within 2 seconds
            2. Extract entities and determine question complexity
            3. Search knowledge base: FAQs, docs, past tickets
            4. Rank results by relevance and freshness
            5. Compose answer: clear, accurate, with citations
            6. Suggest related topics and follow-up questions
            7. Collect user feedback (helpful / not helpful)
            8. Flag knowledge gaps for content team
            9. Escalate to human agent if confidence < threshold
            """;
    }

    public record VerticalScenarioSetup(String teamId, String scenarioId, String scenarioType) {}
}
