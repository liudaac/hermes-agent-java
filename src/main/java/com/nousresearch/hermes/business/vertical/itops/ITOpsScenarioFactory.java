package com.nousresearch.hermes.business.vertical.itops;

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
 * IT 运维垂直场景工厂 - 预置标准运维场景。
 *
 * <p>提供 3 套开箱即用的场景 + 团队蓝图：
 * <ul>
 *   <li>告警分诊（Alert Triage）- 分诊 -> 根因 -> 修复 -> 复盘</li>
 *   <li>变更管理（Change Management）- 申请 -> 评估 -> 执行 -> 验证</li>
 *   <li>巡检健康（Health Check）- 采集 -> 分析 -> 报告 -> 归档</li>
 * </ul>
 */
public class ITOpsScenarioFactory {
    private static final Logger logger = LoggerFactory.getLogger(ITOpsScenarioFactory.class);

    private final WorkspaceService workspaceService;
    private final ScenarioService scenarioService;
    private final TeamBlueprintService teamBlueprintService;

    public ITOpsScenarioFactory(WorkspaceService workspaceService,
                                 ScenarioService scenarioService,
                                 TeamBlueprintService teamBlueprintService) {
        this.workspaceService = workspaceService;
        this.scenarioService = scenarioService;
        this.teamBlueprintService = teamBlueprintService;
    }

    /**
     * 告警分诊场景：告警 -> 分类 -> 根因分析 -> 自动修复 -> 复盘报告
     */
    public VerticalScenarioSetup createAlertTriageScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        String teamId = "alert-triage-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Alert Triage Team",
                "Triage alerts, identify root cause, apply remediation, and generate post-mortem",
                "Alert Triage", null,
                List.of(
                    agent("alert-classifier", "Alert Classifier", "Classify alerts by severity, category, and affected service; deduplicate and suppress noise"),
                    agent("root-cause-analyzer", "Root Cause Analyzer", "Analyze logs, metrics, and traces to identify root cause; correlate with recent changes and deployments"),
                    agent("remediation-executor", "Remediation Executor", "Execute approved remediation actions: restart service, rollback deployment, scale resources, update config"),
                    agent("postmortem-writer", "Postmortem Writer", "Generate incident timeline, root cause summary, action items, and lessons learned")
                ),
                List.of("prompt://alert-triage-standard"),
                getAlertTriageManual(),
                Map.of("vertical", "itops", "domain", "incident_management")
            );
            logger.info("Created alert triage team for workspace {}", workspaceId);
        }

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "alert-triage",
            "Alert Triage", "Triage production alerts from detection to postmortem",
            teamId,
            List.of("Alert classified within 60s", "Root cause identified", "Remediation applied or escalated", "Postmortem within 24h"),
            List.of("high-risk", "external-action"),
            Map.of("sla", "alert_triage", "vertical", "itops"),
            CollaborationPattern.SEQUENTIAL,
            "alert_triage"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "alert-triage");
    }

    /**
     * 变更管理场景：申请 -> 风险评估 -> 执行 -> 验证 -> 归档
     */
    public VerticalScenarioSetup createChangeManagementScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        String teamId = "change-management-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Change Management Team",
                "Review, approve, execute, and verify infrastructure changes",
                "Change Management", null,
                List.of(
                    agent("change-requester", "Change Requester", "Draft change requests with scope, rollback plan, and affected services"),
                    agent("risk-assessor", "Risk Assessor", "Evaluate change risk, check blast radius, verify rollback plan, assess compliance impact"),
                    agent("change-executor", "Change Executor", "Execute approved changes following runbook; monitor for errors; trigger rollback if needed"),
                    agent("verification-agent", "Verification Agent", "Verify change success: run smoke tests, check metrics, confirm service health")
                ),
                List.of("prompt://change-management-standard"),
                getChangeManagementManual(),
                Map.of("vertical", "itops", "domain", "change_management")
            );
            logger.info("Created change management team for workspace {}", workspaceId);
        }

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "change-management",
            "Change Management", "Manage infrastructure changes from request to verification",
            teamId,
            List.of("Risk assessment within 15min", "Change executed per runbook", "Verification passed", "Change archived"),
            List.of("high-risk", "approval-required"),
            Map.of("sla", "change_management", "vertical", "itops"),
            CollaborationPattern.SEQUENTIAL,
            "change_management"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "change-management");
    }

    /**
     * 巡检健康场景：采集 -> 分析 -> 报告 -> 归档
     */
    public VerticalScenarioSetup createHealthCheckScenario(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);

        String teamId = "health-check-team";
        if (!teamBlueprintService.getTeamBlueprint(workspaceId, teamId).isPresent()) {
            teamBlueprintService.createTeamBlueprint(
                workspaceId, teamId, "Health Check Team",
                "Periodic health checks: collect metrics, analyze trends, report status, archive results",
                "Health Check", null,
                List.of(
                    agent("metric-collector", "Metric Collector", "Collect system metrics: CPU, memory, disk, network, service status, latency"),
                    agent("trend-analyzer", "Trend Analyzer", "Analyze metric trends, detect anomalies, compare against baselines and SLOs"),
                    agent("report-generator", "Report Generator", "Generate health report with status summary, anomalies, and recommendations"),
                    agent("archival-agent", "Archival Agent", "Archive health report, update dashboard, notify stakeholders of critical findings")
                ),
                List.of("prompt://health-check-standard"),
                getHealthCheckManual(),
                Map.of("vertical", "itops", "domain", "health_check")
            );
            logger.info("Created health check team for workspace {}", workspaceId);
        }

        ScenarioRecord scenario = scenarioService.createScenario(
            workspaceId, "health-check",
            "Health Check", "Periodic system health check from metric collection to report archival",
            teamId,
            List.of("Metrics collected", "Anomalies flagged", "Report generated", "Critical findings notified"),
            List.of("always"),
            Map.of("sla", "health_check", "vertical", "itops"),
            CollaborationPattern.SEQUENTIAL,
            "health_check"
        );

        return new VerticalScenarioSetup(teamId, scenario.getScenarioId(), "health-check");
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

    private String getAlertTriageManual() {
        return """
            # Alert Triage Operating Manual
            1. Classify alert within 60 seconds (severity, category, affected service)
            2. Deduplicate against active alerts; suppress noise
            3. Identify root cause: check logs, metrics, traces, recent changes
            4. Apply approved remediation or escalate to on-call
            5. Generate postmortem within 24h: timeline, root cause, action items
            6. Update runbook with new findings
            """;
    }

    private String getChangeManagementManual() {
        return """
            # Change Management Operating Manual
            1. Draft change request: scope, affected services, rollback plan
            2. Assess risk: blast radius, compliance, operational impact
            3. Execute change following runbook step-by-step
            4. Monitor for errors during and after change
            5. Trigger rollback if verification fails or errors detected
            6. Verify: run smoke tests, check metrics, confirm service health
            7. Archive change record with outcome and lessons
            """;
    }

    private String getHealthCheckManual() {
        return """
            # Health Check Operating Manual
            1. Collect metrics: CPU, memory, disk, network, service status, latency
            2. Compare against baselines and SLO targets
            3. Detect anomalies and trend deviations
            4. Generate health report: status summary, anomalies, recommendations
            5. Notify stakeholders of critical findings
            6. Archive report for trend analysis and compliance
            """;
    }

    public record VerticalScenarioSetup(String teamId, String scenarioId, String scenarioType) {}
}
