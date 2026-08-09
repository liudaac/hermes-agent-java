package com.nousresearch.hermes.space;

import com.nousresearch.hermes.tenant.core.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Space-level context - the semantic layer over TenantContext.
 *
 * <p>This is the <b>space layer</b> of the three-layer main line:
 * <pre>
 *   Organization  ->  Space  ->  User
 * </pre>
 *
 * <p>SpaceContext wraps the existing TenantContext with a cleaner,
 * business-oriented API. It does NOT replace TenantContext - it
 * exposes its capabilities through the three-layer vocabulary.</p>
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Expose team capabilities via {@link SpaceCapabilityRegistry}</li>
 *   <li>Manage team knowledge base entries</li>
 *   <li>Enforce space-level {@link SpacePolicy}</li>
 *   <li>Track {@link SpaceMember}s</li>
 *   <li>Delegate runtime concerns (sandbox, memory, quota) to TenantContext</li>
 * </ul></p>
 */
public class SpaceContext {

    private static final Logger logger = LoggerFactory.getLogger(SpaceContext.class);

    private final String spaceId;
    private final String spaceName;
    private final TenantContext tenant;  // delegate for runtime concerns

    private SpaceCapabilityRegistry capabilities;
    private SpacePolicy policy;
    private final ConcurrentHashMap<String, KnowledgeEntry> knowledgeBase;
    private final ConcurrentHashMap<String, SpaceMember> members;

    public SpaceContext(String spaceId, String spaceName, TenantContext tenant) {
        this.spaceId = spaceId;
        this.spaceName = spaceName;
        this.tenant = tenant;
        this.capabilities = new SpaceCapabilityRegistry();
        this.policy = new SpacePolicy();
        this.knowledgeBase = new ConcurrentHashMap<>();
        this.members = new ConcurrentHashMap<>();
    }

    // ── Identity ──

    public String spaceId() { return spaceId; }
    public String spaceName() { return spaceName; }
    public TenantContext tenant() { return tenant; }  // escape hatch for runtime ops

    // ── Capabilities ──

    public SpaceCapabilityRegistry capabilities() { return capabilities; }
    public void setCapabilities(SpaceCapabilityRegistry c) { this.capabilities = c; }

    // ── Knowledge ──

    public KnowledgeEntry addKnowledge(KnowledgeEntry entry) {
        knowledgeBase.put(entry.id(), entry);
        logger.info("Space {} knowledge added: {} ({})", spaceId, entry.title(), entry.category());
        return entry;
    }

    public KnowledgeEntry updateKnowledge(String id, String title, String content, String category) {
        KnowledgeEntry existing = knowledgeBase.get(id);
        if (existing == null) return null;
        KnowledgeEntry updated = new KnowledgeEntry(
            id, title != null ? title : existing.title(),
            content != null ? content : existing.content(),
            category != null ? category : existing.category(),
            existing.tags(), existing.authorId(),
            existing.createdAt(), System.currentTimeMillis()
        );
        knowledgeBase.put(id, updated);
        return updated;
    }

    public boolean removeKnowledge(String id) {
        return knowledgeBase.remove(id) != null;
    }

    public KnowledgeEntry getKnowledge(String id) {
        return knowledgeBase.get(id);
    }

    public List<KnowledgeEntry> listKnowledge() {
        return new ArrayList<>(knowledgeBase.values());
    }

    public List<KnowledgeEntry> searchKnowledge(String query) {
        String q = query.toLowerCase();
        return knowledgeBase.values().stream()
            .filter(e -> e.title().toLowerCase().contains(q)
                      || e.content().toLowerCase().contains(q)
                      || e.category().toLowerCase().contains(q)
                      || e.tags().stream().anyMatch(t -> t.toLowerCase().contains(q)))
            .sorted(Comparator.comparingLong(KnowledgeEntry::updatedAt).reversed())
            .toList();
    }

    // ── Policy ──

    public SpacePolicy policy() { return policy; }
    public void setPolicy(SpacePolicy p) { this.policy = p; }

    // ── Members ──

    public SpaceMember addMember(String userId, String displayName, String role) {
        SpaceMember member = new SpaceMember(userId, displayName, role,
            System.currentTimeMillis(), System.currentTimeMillis());
        members.put(userId, member);
        logger.info("Space {} member added: {} ({})", spaceId, displayName, role);
        return member;
    }

    public boolean removeMember(String userId) {
        return members.remove(userId) != null;
    }

    public SpaceMember getMember(String userId) {
        return members.get(userId);
    }

    public List<SpaceMember> listMembers() {
        return new ArrayList<>(members.values());
    }

    public void updateMemberActivity(String userId) {
        SpaceMember m = members.get(userId);
        if (m != null) {
            members.put(userId, new SpaceMember(m.userId(), m.displayName(), m.role(),
                m.joinedAt(), System.currentTimeMillis()));
        }
    }

    // ── Serialization ──

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spaceId", spaceId);
        m.put("spaceName", spaceName);
        m.put("capabilities", capabilities.toMap());
        m.put("policy", policy.toMap());
        m.put("knowledge", knowledgeBase.values().stream().map(KnowledgeEntry::toMap).toList());
        m.put("members", members.values().stream().map(SpaceMember::toMap).toList());
        return m;
    }
}
