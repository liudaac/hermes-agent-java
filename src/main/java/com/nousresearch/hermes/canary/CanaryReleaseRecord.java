package com.nousresearch.hermes.canary;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canary release record for gradual rollout of blueprint versions. */
public class CanaryReleaseRecord {
    public static final String ACTIVE = "ACTIVE";
    public static final String COMPLETED = "COMPLETED";
    public static final String ROLLED_BACK = "ROLLED_BACK";

    private String workspaceId;
    private String teamId;
    private String releaseId;
    private int fromVersion;
    private int toVersion;
    private int trafficPercent; // 0-100
    private String status;
    private Map<String, Object> metrics = new LinkedHashMap<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public String getWorkspaceId() { return workspaceId; }
    public CanaryReleaseRecord setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; return this; }
    public String getTeamId() { return teamId; }
    public CanaryReleaseRecord setTeamId(String teamId) { this.teamId = teamId; return this; }
    public String getReleaseId() { return releaseId; }
    public CanaryReleaseRecord setReleaseId(String releaseId) { this.releaseId = releaseId; return this; }
    public int getFromVersion() { return fromVersion; }
    public CanaryReleaseRecord setFromVersion(int fromVersion) { this.fromVersion = fromVersion; return this; }
    public int getToVersion() { return toVersion; }
    public CanaryReleaseRecord setToVersion(int toVersion) { this.toVersion = toVersion; return this; }
    public int getTrafficPercent() { return trafficPercent; }
    public CanaryReleaseRecord setTrafficPercent(int trafficPercent) { this.trafficPercent = trafficPercent; return this; }
    public String getStatus() { return status; }
    public CanaryReleaseRecord setStatus(String status) { this.status = status; return this; }
    public Map<String, Object> getMetrics() { return metrics; }
    public CanaryReleaseRecord setMetrics(Map<String, Object> metrics) { this.metrics = metrics != null ? metrics : new LinkedHashMap<>(); return this; }
    public Map<String, Object> getMetadata() { return metadata; }
    public CanaryReleaseRecord setMetadata(Map<String, Object> metadata) { this.metadata = metadata != null ? metadata : new LinkedHashMap<>(); return this; }
    public Instant getCreatedAt() { return createdAt; }
    public CanaryReleaseRecord setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
    public Instant getUpdatedAt() { return updatedAt; }
    public CanaryReleaseRecord setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
}
