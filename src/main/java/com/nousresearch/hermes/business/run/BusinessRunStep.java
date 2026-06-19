package com.nousresearch.hermes.business.run;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One business-readable step in a run story. */
public class BusinessRunStep {
    private String stepId;
    private String title;
    private String summary;
    private String actor;
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
        map.put("evidence", evidence);
        map.put("status", status);
        map.put("timestamp", timestamp != null ? timestamp.toString() : null);
        if (metadata != null && !metadata.isEmpty()) {
            map.put("metadata", metadata);
        }
        return map;
    }
}
