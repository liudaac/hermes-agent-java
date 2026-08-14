package com.nousresearch.hermes.dashboard.handlers;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.improvement.ImprovementProposal;
import com.nousresearch.hermes.improvement.ImprovementSignal;
import com.nousresearch.hermes.improvement.ProposalStore;
import com.nousresearch.hermes.improvement.SignalStore;
import com.nousresearch.hermes.improvement.SignalScope;
import com.nousresearch.hermes.memory.store.MemoryEntry;
import com.nousresearch.hermes.memory.store.MemoryStore;
import com.nousresearch.hermes.org.OrgManager;
import com.nousresearch.hermes.space.SpaceContext;
import com.nousresearch.hermes.space.SpaceMember;
import com.nousresearch.hermes.space.KnowledgeEntry;
import com.nousresearch.hermes.user.UserProfile;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Three-layer admin API handler.
 *
 * <p>Exposes the User -> Space -> Org intervention points for the frontend:
 * <ul>
 *   <li>{@code /api/org/*} - organization-level management</li>
 *   <li>{@code /api/spaces/*} - space-level management</li>
 *   <li>{@code /api/users/*} - user-level management</li>
 *   <li>{@code /api/improvement/*} - cross-layer improvement signals</li>
 * </ul></p>
 */
public class ThreeLayerAdminHandler {

    private static final Logger logger = LoggerFactory.getLogger(ThreeLayerAdminHandler.class);

    private final OrgManager orgManager;
    private SignalStore signalStore;
    private ProposalStore proposalStore;
    private MemoryStore memoryStore;

    public ThreeLayerAdminHandler(OrgManager orgManager) {
        this.orgManager = orgManager;
    }

    public void setSignalStore(SignalStore store) { this.signalStore = store; }
    public void setProposalStore(ProposalStore store) { this.proposalStore = store; }
    public void setMemoryStore(MemoryStore store) { this.memoryStore = store; }

    // ═══════════════════════════════════════════════════════════════
    //  Organization Layer
    // ═══════════════════════════════════════════════════════════════

    public void orgOverview(Context ctx) {
        ctx.json(JSON.toJSON(orgManager.overview()));
    }

    public void orgModels(Context ctx) {
        ctx.json(JSON.toJSON(orgManager.org().modelCatalog().toMap()));
    }

    public void orgUsers(Context ctx) {
        List<UserProfile> users = orgManager.users().listCached();
        List<Map<String, Object>> result = users.stream()
            .map(u -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", u.userId());
                m.put("displayName", u.displayName());
                m.put("email", u.email());
                m.put("channels", new LinkedHashMap<>(u.channelBindings()));
                m.put("spaceCount", u.spaces().size());
                m.put("capabilities", u.capabilities().toMap());
                m.put("preferences", u.preferences().toMap());
                return m;
            }).toList();
        ctx.json(Map.of("ok", true, "users", result, "total", result.size()));
    }

    public void orgSpaces(Context ctx) {
        List<Map<String, Object>> spaces = orgManager.spaces().listCached().stream()
            .map(s -> orgManager.spaces().overview(s.spaceId()))
            .toList();
        ctx.json(Map.of("ok", true, "spaces", spaces, "total", spaces.size()));
    }

    public void orgBilling(Context ctx) {
        ctx.json(Map.of("ok", true, "billing", orgManager.org().billingSummary()));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Space Layer
    // ═══════════════════════════════════════════════════════════════

    public void spaceOverview(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        ctx.json(Map.of("ok", true, "overview", orgManager.spaces().overview(spaceId)));
    }

    public void spaceCapabilities(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        SpaceContext space = orgManager.spaces().load(spaceId);
        ctx.json(Map.of("ok", true, "capabilities", space.capabilities().toMap()));
    }

    public void spaceInstallSkill(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        String skillId = body.getString("skillId");
        orgManager.spaces().load(spaceId).capabilities().installSkill(skillId);
        ctx.json(Map.of("ok", true, "skillId", skillId));
    }

    public void spaceUninstallSkill(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        String skillId = ctx.pathParam("skillId");
        orgManager.spaces().load(spaceId).capabilities().uninstallSkill(skillId);
        ctx.json(Map.of("ok", true, "removed", skillId));
    }

    public void spaceKnowledge(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        SpaceContext space = orgManager.spaces().load(spaceId);
        ctx.json(Map.of("ok", true, "entries", space.listKnowledge()));
    }

    public void spaceAddKnowledge(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        String id = "k_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        KnowledgeEntry entry = new KnowledgeEntry(
            id,
            body.getString("title"),
            body.getString("content"),
            body.containsKey("category") ? body.getString("category") : "domain",
            body.getJSONArray("tags") != null
                ? body.getJSONArray("tags").toJavaList(String.class) : List.of(),
            body.containsKey("authorId") ? body.getString("authorId") : "",
            System.currentTimeMillis(), System.currentTimeMillis()
        );
        orgManager.spaces().load(spaceId).addKnowledge(entry);
        ctx.json(Map.of("ok", true, "entry", entry.toMap()));
    }

    public void spaceUpdateKnowledge(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        String entryId = ctx.pathParam("entryId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        KnowledgeEntry updated = orgManager.spaces().load(spaceId).updateKnowledge(
            entryId,
            body.getString("title"),
            body.getString("content"),
            body.containsKey("category") ? body.getString("category") : "domain"
        );
        if (updated == null) {
            ctx.status(404).json(Map.of("ok", false, "error", "Knowledge entry not found"));
        } else {
            ctx.json(Map.of("ok", true, "entry", updated.toMap()));
        }
    }

    public void spaceDeleteKnowledge(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        String entryId = ctx.pathParam("entryId");
        boolean removed = orgManager.spaces().load(spaceId).removeKnowledge(entryId);
        ctx.json(Map.of("ok", removed));
    }

    public void spaceSearchKnowledge(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        String q = ctx.queryParam("q");
        List<KnowledgeEntry> results = orgManager.spaces().load(spaceId).searchKnowledge(q != null ? q : "");
        ctx.json(Map.of("ok", true, "results", results, "total", results.size()));
    }

    public void spacePolicy(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        SpaceContext space = orgManager.spaces().load(spaceId);
        ctx.json(Map.of("ok", true, "policy", space.policy().toMap()));
    }

    public void spaceUpdatePolicy(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        SpaceContext space = orgManager.spaces().load(spaceId);
        if (body.containsKey("decayPolicy")) {
            space.policy().setDecayPolicy(body.getString("decayPolicy"));
        }
        if (body.containsKey("sandboxEnforced")) {
            space.policy().setSandboxEnforced(body.getBoolean("sandboxEnforced"));
        }
        if (body.containsKey("maxConcurrentRuns")) {
            space.policy().setMaxConcurrentRuns(body.getIntValue("maxConcurrentRuns", 5));
        }
        if (body.containsKey("allowUserOverride")) {
            space.policy().setAllowUserOverride(body.getBoolean("allowUserOverride"));
        }
        if (body.containsKey("approvalModes")) {
            JSONObject modes = body.getJSONObject("approvalModes");
            for (String key : modes.keySet()) {
                space.policy().setApprovalMode(key, modes.getString(key));
            }
        }
        ctx.json(Map.of("ok", true, "policy", space.policy().toMap()));
    }

    public void spaceMembers(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        SpaceContext space = orgManager.spaces().load(spaceId);
        ctx.json(Map.of("ok", true, "members", space.listMembers()));
    }

    public void spaceAddMember(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        SpaceMember member = orgManager.spaces().load(spaceId).addMember(
            body.getString("userId"),
            body.getString("displayName") != null ? body.getString("displayName") : body.getString("userId"),
            body.containsKey("role") ? body.getString("role") : "member"
        );
        ctx.json(Map.of("ok", true, "member", member.toMap()));
    }

    public void spaceRemoveMember(Context ctx) {
        String spaceId = ctx.pathParam("spaceId");
        String userId = ctx.pathParam("userId");
        boolean removed = orgManager.spaces().load(spaceId).removeMember(userId);
        ctx.json(Map.of("ok", removed));
    }

    // ═══════════════════════════════════════════════════════════════
    //  User Layer
    // ═══════════════════════════════════════════════════════════════

    public void userProfile(Context ctx) {
        String userId = ctx.pathParam("userId");
        UserProfile profile = orgManager.users().load(userId);
        ctx.json(Map.of("ok", true, "profile", profile.toMap()));
    }

    public void userUpdateProfile(Context ctx) {
        String userId = ctx.pathParam("userId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        UserProfile profile = orgManager.users().load(userId);
        if (body.containsKey("displayName")) {
            profile.setDisplayName(body.getString("displayName"));
        }
        if (body.containsKey("email")) {
            profile.setEmail(body.getString("email"));
        }
        orgManager.users().save(profile);
        ctx.json(Map.of("ok", true, "profile", profile.toMap()));
    }

    public void userCapabilities(Context ctx) {
        String userId = ctx.pathParam("userId");
        UserProfile profile = orgManager.users().load(userId);
        ctx.json(Map.of("ok", true, "capabilities", profile.capabilities().toMap()));
    }

    public void userAddCapability(Context ctx) {
        String userId = ctx.pathParam("userId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        UserProfile profile = orgManager.users().load(userId);
        String type = body.containsKey("type") ? body.getString("type") : "skill";
        String value = body.getString("value");
        if ("skill".equals(type)) {
            profile.capabilities().addPersonalSkill(value);
        } else if ("tool".equals(type)) {
            profile.capabilities().addFrequentTool(value);
        } else if ("shortcut".equals(type)) {
            String alias = body.getString("alias");
            profile.capabilities().addShortcut(alias, value);
        } else if ("hide".equals(type)) {
            profile.capabilities().hide(value);
        }
        orgManager.users().save(profile);
        ctx.json(Map.of("ok", true, "capabilities", profile.capabilities().toMap()));
    }

    public void userRemoveCapability(Context ctx) {
        String userId = ctx.pathParam("userId");
        String type = ctx.pathParam("type");
        String value = ctx.pathParam("value");
        UserProfile profile = orgManager.users().load(userId);
        if ("skill".equals(type)) {
            profile.capabilities().removePersonalSkill(value);
        } else if ("shortcut".equals(type)) {
            profile.capabilities().removeShortcut(value);
        } else if ("hide".equals(type)) {
            profile.capabilities().unhide(value);
        }
        orgManager.users().save(profile);
        ctx.json(Map.of("ok", true, "capabilities", profile.capabilities().toMap()));
    }

    public void userPreferences(Context ctx) {
        String userId = ctx.pathParam("userId");
        UserProfile profile = orgManager.users().load(userId);
        ctx.json(Map.of("ok", true, "preferences", profile.preferences().toMap()));
    }

    public void userUpdatePreferences(Context ctx) {
        String userId = ctx.pathParam("userId");
        JSONObject body = ctx.bodyAsClass(JSONObject.class);
        UserProfile profile = orgManager.users().load(userId);
        if (body.containsKey("language")) {
            profile.preferences().setLanguage(body.getString("language"));
        }
        if (body.containsKey("responseStyle")) {
            profile.preferences().setResponseStyle(body.getString("responseStyle"));
        }
        if (body.containsKey("tone")) {
            profile.preferences().setTone(body.getString("tone"));
        }
        if (body.containsKey("autoApproveSafe")) {
            profile.preferences().setAutoApproveSafe(body.getBoolean("autoApproveSafe"));
        }
        if (body.containsKey("maxContextChars")) {
            profile.preferences().setMaxContextChars(body.getIntValue("maxContextChars", 400000));
        }
        orgManager.users().save(profile);
        ctx.json(Map.of("ok", true, "preferences", profile.preferences().toMap()));
    }

    public void userSpaces(Context ctx) {
        String userId = ctx.pathParam("userId");
        UserProfile profile = orgManager.users().load(userId);
        ctx.json(Map.of("ok", true, "spaces", profile.spaces()));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Improvement (cross-layer)
    // ═══════════════════════════════════════════════════════════════

    public void improvementSignals(Context ctx) {
        String scope = ctx.queryParam("scope");
        String userId = ctx.queryParam("userId");
        String spaceId = ctx.queryParam("spaceId");
        String tenantId = spaceId != null ? spaceId : "default";

        if (signalStore == null) {
            ctx.json(Map.of("ok", true, "signals", List.of(), "scope", scope != null ? scope : "all"));
            return;
        }

        List<ImprovementSignal> signals;
        if (userId != null && !userId.isBlank()) {
            signals = signalStore.queryByUser(tenantId, userId);
        } else {
            signals = signalStore.queryByUser(tenantId, null);
        }

        // Filter by scope if specified
        if (scope != null && !scope.isBlank() && !scope.equals("all")) {
            SignalScope filterScope = SignalScope.valueOf(scope.toUpperCase());
            signals = signals.stream()
                .filter(s -> s.scope() == filterScope)
                .toList();
        }

        List<Map<String, Object>> result = signals.stream()
            .sorted(Comparator.comparingLong(ImprovementSignal::timestamp).reversed())
            .limit(100)
            .map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id());
                m.put("type", s.type().name());
                m.put("scope", s.scope() != null ? s.scope().name() : "USER");
                m.put("userId", s.userId());
                m.put("content", s.content());
                m.put("weight", s.weight());
                m.put("timestamp", s.timestamp());
                m.put("processed", s.processed());
                return m;
            }).toList();

        ctx.json(Map.of("ok", true, "signals", result, "total", result.size()));
    }

    public void improvementProposals(Context ctx) {
        String scope = ctx.queryParam("scope");
        String userId = ctx.queryParam("userId");
        String spaceId = ctx.queryParam("spaceId");
        String tenantId = spaceId != null ? spaceId : "default";

        if (proposalStore == null) {
            ctx.json(Map.of("ok", true, "proposals", List.of(), "scope", scope != null ? scope : "all"));
            return;
        }

        List<ImprovementProposal> proposals;
        if (userId != null && !userId.isBlank()) {
            proposals = proposalStore.queryByUser(tenantId, userId);
        } else {
            proposals = proposalStore.queryAll(tenantId);
        }

        ctx.json(Map.of("ok", true, "proposals", proposals, "total", proposals.size()));
    }

    public void improvementAdaptations(Context ctx) {
        String userId = ctx.queryParam("userId");
        UserProfile profile = orgManager.users().load(userId);

        // Also fetch user memories from MemoryStore if available
        List<Map<String, Object>> memories = List.of();
        if (memoryStore != null) {
            try {
                var entries = memoryStore.searchMemories("default", userId, "", 20);
                memories = entries.stream()
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", e.getId());
                        m.put("type", e.getType() != null ? e.getType().name() : "UNKNOWN");
                        m.put("content", e.getContent());
                        m.put("category", e.getCategory());
                        m.put("createdAt", e.getCreatedAt());
                        return m;
                    }).toList();
            } catch (Exception e) {
                logger.debug("Failed to fetch memories for adaptations: {}", e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("userId", userId);
        result.put("preferences", profile.preferences().toMap());
        result.put("capabilities", profile.capabilities().toMap());
        result.put("memories", memories);
        ctx.json(result);
    }
}
