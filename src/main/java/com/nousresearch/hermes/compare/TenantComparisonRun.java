package com.nousresearch.hermes.compare;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side multi-tenant comparison run state.
 */
public class TenantComparisonRun {
    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        STOPPED,
        FAILED
    }

    private final String id;
    private final String topic;
    private final int rounds;
    private final List<Participant> participants;
    private final List<Event> events = new ArrayList<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Status status = Status.PENDING;
    private volatile String conclusion = "";
    private volatile String error;
    private final Instant createdAt;
    private volatile Instant updatedAt;

    public TenantComparisonRun(String topic, int rounds, List<String> tenantIds) {
        this.id = UUID.randomUUID().toString();
        this.topic = topic;
        this.rounds = Math.max(1, rounds);
        this.participants = new ArrayList<>();
        for (String tenantId : tenantIds) {
            this.participants.add(new Participant(tenantId));
        }
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getTopic() { return topic; }
    public int getRounds() { return rounds; }
    public List<Participant> getParticipants() { return participants; }
    public Status getStatus() { return status; }
    public String getConclusion() { return conclusion; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isStopRequested() { return stopRequested.get(); }

    public void markRunning() { setStatus(Status.RUNNING); }
    public void markCompleted(String conclusion) { this.conclusion = conclusion != null ? conclusion : ""; setStatus(Status.COMPLETED); }
    public void markStopped() { setStatus(Status.STOPPED); }
    public void markFailed(String error) { this.error = error; setStatus(Status.FAILED); }
    public void requestStop() { stopRequested.set(true); touch(); }

    public synchronized void addEvent(String tenantId, String role, String content) {
        events.add(new Event(tenantId, role, content, Instant.now()));
        touch();
    }

    public synchronized List<Event> getEvents() {
        return new ArrayList<>(events);
    }

    private void setStatus(Status status) {
        this.status = status;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Map<String, Object> toSummaryMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("topic", topic);
        map.put("rounds", rounds);
        map.put("status", status.name());
        map.put("participants", participants.stream().map(Participant::toMap).toList());
        map.put("conclusion", conclusion);
        map.put("error", error);
        map.put("created_at", createdAt.toString());
        map.put("updated_at", updatedAt.toString());
        map.put("event_count", events.size());
        return map;
    }

    public Map<String, Object> toDetailMap() {
        Map<String, Object> map = toSummaryMap();
        map.put("events", getEvents().stream().map(Event::toMap).toList());
        return map;
    }

    public static class Participant {
        private final String tenantId;
        private final String sessionId;

        public Participant(String tenantId) {
            this.tenantId = tenantId;
            this.sessionId = "compare-" + UUID.randomUUID();
        }

        public String getTenantId() { return tenantId; }
        public String getSessionId() { return sessionId; }

        public Map<String, Object> toMap() {
            return Map.of("tenant_id", tenantId, "session_id", sessionId);
        }
    }

    public record Event(String tenantId, String role, String content, Instant timestamp) {
        public Map<String, Object> toMap() {
            return Map.of(
                "tenant_id", tenantId,
                "role", role,
                "content", content,
                "timestamp", timestamp.toString()
            );
        }
    }
}
