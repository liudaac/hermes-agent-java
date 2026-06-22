package com.nousresearch.hermes.business.run;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务运行步骤 — 运行记录中的一个原子执行单元。
 *
 * <p>每个步骤描述一个 Agent（或系统）完成的动作，包含：
 * <ul>
 *   <li>actor / agentId: 执行者标识</li>
 *   <li>score / matchedSkills: Agent 匹配分数和技能（用于调试和优化）</li>
 *   <li>retry / retryFrom: 重试标记和来源（展示故障转移路径）</li>
 *   <li>evidence: 执行证据（如 traceId、错误信息）</li>
 *   <li>metadata: 扩展字段（reflection、confidence 等）</li>
 * </ul>
 */
public class BusinessRunStep {
    private String stepId;
    private String title;
    private String summary;
    private String actor;
    private String agentId;
    private Double score;
    private String matchedSkills;
    private Boolean retry;
    private String retryFrom;
    private String evidence;
    private String status = "COMPLETED";
    private Instant timestamp;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public BusinessRunStep() {
    }

    public String getStepId() { return stepId; }
    public BusinessRunStep setStepId(String stepId) { this.stepId = stepId; return this; }
    public String getTitle() { return title; }
    public BusinessRunStep setTitle(String title) { this.title = title; return this; }
    public String getSummary() { return summary; }
    public BusinessRunStep setSummary(String summary) { this.summary = summary; return this; }
    public String getActor() { return actor; }
    public BusinessRunStep setActor(String actor) { this.actor = actor; return this; }
    public String getAgentId() { return agentId; }
    public BusinessRunStep setAgentId(String agentId) { this.agentId = agentId; return this; }
    public Double getScore() { return score; }
    public BusinessRunStep setScore(Double score) { this.score = score; return this; }
    public String getMatchedSkills() { return matchedSkills; }
    public BusinessRunStep setMatchedSkills(String matchedSkills) { this.matchedSkills = matchedSkills; return this; }
    public Boolean getRetry() { return retry; }
    public BusinessRunStep setRetry(Boolean retry) { this.retry = retry; return this; }
    public String getRetryFrom() { return retryFrom; }
    public BusinessRunStep setRetryFrom(String retryFrom) { this.retryFrom = retryFrom; return this; }
    public String getEvidence() { return evidence; }
    public BusinessRunStep setEvidence(String evidence) { this.evidence = evidence; return this; }
    public String getStatus() { return status; }
    public BusinessRunStep setStatus(String status) { this.status = status; return this; }
    public Instant getTimestamp() { return timestamp; }
    public BusinessRunStep setTimestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public BusinessRunStep setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stepId", stepId);
        map.put("title", title);
        map.put("summary", summary);
        map.put("actor", actor);
        map.put("agentId", agentId);
        map.put("score", score);
        map.put("matchedSkills", matchedSkills);
        map.put("retry", retry);
        map.put("retryFrom", retryFrom);
        map.put("evidence", evidence);
        map.put("status", status);
        map.put("timestamp", timestamp != null ? timestamp.toString() : null);
        if (metadata != null && !metadata.isEmpty()) {
            map.put("metadata", metadata);
        }
        return map;
    }
}
