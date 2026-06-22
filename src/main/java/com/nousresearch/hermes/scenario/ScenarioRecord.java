package com.nousresearch.hermes.scenario;

import com.nousresearch.hermes.collaboration.pattern.CollaborationPattern;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 业务场景 — 将业务目标与入口团队蓝图关联的可复用配置。
 *
 * <p>新增编排字段：
 * <ul>
 *   <li>collaborationPattern: 多 Agent 协作模式（默认 SEQUENTIAL）</li>
 *   <li>slaName: 绑定的 SLA 策略名称</li>
 * </ul>
 * <p>运行时会根据这些字段自动设置 BusinessRunRecord 的对应属性。</p>
 */
public class ScenarioRecord {
    private String workspaceId;
    private String scenarioId;
    private String name;
    private String description;
    private String entryTeamId;
    private String status = "ACTIVE";
    private List<String> successCriteria = new ArrayList<>();
    private List<String> approvalRules = new ArrayList<>();
    private CollaborationPattern collaborationPattern = CollaborationPattern.SEQUENTIAL;
    private String slaName;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public ScenarioRecord() {
    }

    /** 获取WorkspaceId。 */
    public String getWorkspaceId() { return workspaceId; }
    public ScenarioRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    /** 获取ScenarioId。 */
    public String getScenarioId() { return scenarioId; }
    public ScenarioRecord setScenarioId(String scenarioId) { this.scenarioId = scenarioId; return this; }
    /** 获取Name。 */
    public String getName() { return name; }
    public ScenarioRecord setName(String name) { this.name = name; return this; }
    /** 获取Description。 */
    public String getDescription() { return description; }
    public ScenarioRecord setDescription(String description) { this.description = description; return this; }
    /** 获取EntryTeamId。 */
    public String getEntryTeamId() { return entryTeamId; }
    public ScenarioRecord setEntryTeamId(String entryTeamId) { this.entryTeamId = entryTeamId; return this; }
    /** 获取Status。 */
    public String getStatus() { return status; }
    public ScenarioRecord setStatus(String status) { this.status = status; return this; }
    /** 获取SuccessCriteria。 */
    public List<String> getSuccessCriteria() { return successCriteria; }
    public ScenarioRecord setSuccessCriteria(List<String> successCriteria) { this.successCriteria = successCriteria != null ? successCriteria : new ArrayList<>(); return this; }
    /** 获取ApprovalRules。 */
    public List<String> getApprovalRules() { return approvalRules; }
    public ScenarioRecord setApprovalRules(List<String> approvalRules) { this.approvalRules = approvalRules != null ? approvalRules : new ArrayList<>(); return this; }
    /** 获取CollaborationPattern。 */
    public CollaborationPattern getCollaborationPattern() { return collaborationPattern; }
    public ScenarioRecord setCollaborationPattern(CollaborationPattern collaborationPattern) { this.collaborationPattern = collaborationPattern != null ? collaborationPattern : CollaborationPattern.SEQUENTIAL; return this; }
    /** 获取SlaName。 */
    public String getSlaName() { return slaName; }
    public ScenarioRecord setSlaName(String slaName) { this.slaName = slaName; return this; }
    /** 获取Metadata。 */
    public Map<String, Object> getMetadata() { return metadata; }
    public ScenarioRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    /** 获取CreatedAt。 */
    public Instant getCreatedAt() { return createdAt; }
    public ScenarioRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    /** 获取UpdatedAt。 */
    public Instant getUpdatedAt() { return updatedAt; }
    public ScenarioRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
}
