package com.nousresearch.hermes.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A team is a group of AI agents within a tenant that share
 * a common mission, a blackboard, and awareness of each other.
 *
 * <p>Teams are the unit of coordination in the AI-native organization.
 * Unlike ad-hoc agent collaboration, teams have:
 * <ul>
 *   <li>A shared mission (what the team exists to do)</li>
 *   <li>Explicit membership (which agents belong)</li>
 *   <li>A team leader (the senior/lead agent that arbitrates)</li>
 *   <li>Team-level health and status</li>
 *   <li>Shared context accessible to all members</li>
 * </ul>
 *
 * <p>Members of a team are automatically registered on the TenantBus
 * so they can exchange messages, escalate decisions, and coordinate.</p>
 */
public class Team {
    private static final Logger logger = LoggerFactory.getLogger(Team.class);

    private final String teamId;
    private final String name;
    private final String mission;
    private final String tenantId;
    private final String createdBy;

    // Membership
    private final Set<String> memberIds = ConcurrentHashMap.newKeySet();
    private volatile String leadAgentId;

    // Team-level shared state (decisions, statuses, notes)
    private final ConcurrentHashMap<String, Object> sharedState = new ConcurrentHashMap<>();

    // Activity log
    private final LinkedList<TeamActivity> recentActivity = new LinkedList<>();
    private static final int MAX_ACTIVITY = 50;

    // Metadata
    private final Instant createdAt = Instant.now();
    private volatile Instant lastActivity = Instant.now();

    public Team(String teamId, String name, String mission, String tenantId, String createdBy) {
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.name = Objects.requireNonNull(name, "name");
        this.mission = mission != null ? mission : "";
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.createdBy = createdBy != null ? createdBy : "system";
    }

    // ======== Membership ========

    public boolean addMember(String agentId) {
        if (agentId == null || agentId.isBlank()) return false;
        boolean added = memberIds.add(agentId);
        if (added) {
            recordActivity("member_joined", agentId, agentId + " joined the team");
            lastActivity = Instant.now();
            logger.info("Team {} ({}): {} joined", teamId, name, agentId);
        }
        return added;
    }

    public boolean removeMember(String agentId) {
        boolean removed = memberIds.remove(agentId);
        if (removed) {
            recordActivity("member_left", agentId, agentId + " left the team");
            if (agentId.equals(leadAgentId)) {
                leadAgentId = null;
            }
            lastActivity = Instant.now();
        }
        return removed;
    }

    public boolean hasMember(String agentId) {
        return memberIds.contains(agentId);
    }

    public Set<String> getMemberIds() {
        return Collections.unmodifiableSet(memberIds);
    }

    public int size() {
        return memberIds.size();
    }

    public void setLead(String agentId) {
        if (agentId != null && !memberIds.contains(agentId)) {
            addMember(agentId);
        }
        this.leadAgentId = agentId;
        if (agentId != null) {
            recordActivity("lead_assigned", agentId, agentId + " assigned as team lead");
        }
    }

    public String getLead() {
        return leadAgentId;
    }

    // ======== Shared State ========

    public void putState(String key, Object value) {
        sharedState.put(key, value);
        recordActivity("state_updated", null, "State key: " + key);
    }

    public Object getState(String key) {
        return sharedState.get(key);
    }

    public Map<String, Object> getState() {
        return Collections.unmodifiableMap(sharedState);
    }

    // ======== Activity Log ========

    public synchronized List<TeamActivity> getRecentActivity(int limit) {
        List<TeamActivity> out = new ArrayList<>(recentActivity);
        Collections.reverse(out);
        return out.subList(0, Math.min(out.size(), limit));
    }

    private synchronized void recordActivity(String type, String actor, String detail) {
        recentActivity.addLast(new TeamActivity(type, actor, detail, Instant.now()));
        while (recentActivity.size() > MAX_ACTIVITY) {
            recentActivity.pollFirst();
        }
    }

    // ======== Queries ========

    /**
     * Get a description of the team for system prompt injection.
     * Includes mission, members, and recent activity.
     */
    public String describeForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Team: ").append(name).append("\n");
        if (!mission.isEmpty()) {
            sb.append("**Mission**: ").append(mission).append("\n");
        }
        if (leadAgentId != null) {
            sb.append("**Lead**: ").append(leadAgentId).append("\n");
        }
        sb.append("**Members** (").append(memberIds.size()).append("): ");
        sb.append(String.join(", ", memberIds));
        sb.append("\n");

        // Recent activity (last 5)
        var recent = getRecentActivity(5);
        if (!recent.isEmpty()) {
            sb.append("\n## Recent Team Activity\n");
            for (var a : recent) {
                sb.append("- [").append(a.type).append("] ");
                if (a.actor != null) sb.append(a.actor).append(": ");
                sb.append(a.detail).append("\n");
            }
        }
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("team_id", teamId);
        m.put("name", name);
        m.put("mission", mission);
        m.put("tenant_id", tenantId);
        m.put("created_by", createdBy);
        m.put("members", new ArrayList<>(memberIds));
        m.put("lead", leadAgentId);
        m.put("created_at", createdAt.toString());
        m.put("last_activity", lastActivity.toString());
        m.put("size", memberIds.size());
        return m;
    }

    // ======== Getters ========

    public String getTeamId() { return teamId; }
    public String getName() { return name; }
    public String getMission() { return mission; }
    public String getTenantId() { return tenantId; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActivity() { return lastActivity; }

    public record TeamActivity(String type, String actor, String detail, Instant timestamp) {}
}
