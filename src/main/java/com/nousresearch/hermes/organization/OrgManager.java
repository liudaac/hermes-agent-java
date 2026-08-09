package com.nousresearch.hermes.organization;

import com.nousresearch.hermes.auth.UserIdentityResolver;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.improvement.ImprovementSignal;
import com.nousresearch.hermes.org.compliance.ComplianceFramework;
import com.nousresearch.hermes.org.evolution.SelfEvolutionEngine;
import com.nousresearch.hermes.org.knowledge.OrganizationalKnowledgeBase;
import com.nousresearch.hermes.org.market.AgentMarketplace;
import com.nousresearch.hermes.org.observe.AgentObservability;
import com.nousresearch.hermes.space.SpaceManager;
import com.nousresearch.hermes.user.UserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages the organization-level context and wires the three layers together.
 *
 * <p>This is the top-level orchestrator that connects:
 * <pre>
 *   OrgContext (org layer)
 *     └── SpaceManager (space layer)
 *           └── UserManager (user layer)
 * </pre></p>
 *
 * <p>The message flow uses {@link #processMessage} as the single entry point
 * that walks through all three layers:</p>
 * <ol>
 *   <li>Resolve user identity (org layer)</li>
 *   <li>Load user profile (user layer)</li>
 *   <li>Enter space (space layer)</li>
 *   <li>Merge capabilities across layers</li>
 *   <li>Return the assembled context for the agent</li>
 * </ol>
 */
public class OrgManager {

    private static final Logger logger = LoggerFactory.getLogger(OrgManager.class);

    private final OrgContext org;
    private final SpaceManager spaceManager;
    private final UserManager userManager;

    public OrgManager(HermesConfig config, SpaceManager spaceManager, UserManager userManager) {
        this.org = new OrgContext(config);
        this.spaceManager = spaceManager;
        this.userManager = userManager;
    }

    // ── Layer Accessors ──

    public OrgContext org() { return org; }
    public SpaceManager spaces() { return spaceManager; }
    public UserManager users() { return userManager; }

    // ── Wiring ──

    public void setIdentityResolver(UserIdentityResolver r) {
        org.setIdentityResolver(r);
    }

    public void setCompliance(ComplianceFramework c) {
        org.setCompliance(c);
    }

    public void setMarketplace(AgentMarketplace m) {
        org.setMarketplace(m);
    }

    public void setKnowledge(OrganizationalKnowledgeBase k) {
        org.setKnowledge(k);
    }

    public void setObservability(AgentObservability o) {
        org.setObservability(o);
    }

    // ── Main Message Flow ──

    /**
     * Assemble the full three-layer context for a user message.
     *
     * <p>This is the <b>main line</b> - called by the gateway when a user
     * sends a message. It walks through all three layers and returns the
     * assembled context that the agent needs to process the message.</p>
     *
     * @param channel       message channel ("qqbot", "feishu", etc.)
     * @param channelUserId raw user ID from the channel
     * @param spaceId       target space ID (or null for default)
     * @return assembled context, or null if resolution failed
     */
    public AssembledContext assemble(String channel, String channelUserId, String spaceId) {
        // 1. Resolve identity (org layer)
        String userId = userManager.resolveUserId(channel, channelUserId);
        if (userId == null) {
            logger.warn("Failed to resolve user identity: {}/{}", channel, channelUserId);
            return null;
        }

        // 2. Load user profile (user layer)
        var userProfile = userManager.load(userId);

        // 3. Determine target space
        String resolvedSpaceId = spaceId;
        if (resolvedSpaceId == null || resolvedSpaceId.isBlank()) {
            // Use first space membership, or default tenant
            resolvedSpaceId = userProfile.spaces().isEmpty()
                ? "default"
                : userProfile.spaces().get(0).spaceId();
        }

        // 4. Enter space (space layer)
        var spaceContext = spaceManager.enter(resolvedSpaceId, userId);

        // 5. Merge capabilities across layers
        var merged = userManager.mergeCapabilities(
            userProfile,
            spaceContext.capabilities().installedSkills()
        );

        logger.debug("Assembled context: user={} space={} skills={} tools={}",
            userId, resolvedSpaceId, merged.effectiveSkills().size(),
            merged.frequentTools().size());

        return new AssembledContext(userId, userProfile, spaceContext, merged, org);
    }

    // ── Improvement Routing ──

    /**
     * Route an improvement signal to the correct layer.
     */
    public void routeSignal(ImprovementSignal signal, String userId, String spaceId) {
        switch (signal.scope()) {
            case USER -> userManager.adapt(userId, signal);
            case SPACE -> spaceManager.evolve(spaceId, signal);
            case ORG -> {
                logger.info("Org-level signal: {} from space {}", signal.type(), spaceId);
                // TODO: feed into org observability / evolution
            }
        }
    }

    // ── Admin Overview ──

    /**
     * Get the full organization overview for the admin dashboard.
     */
    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("org", org.toMap());
        m.put("spaces", spaceManager.listCached().stream()
            .map(s -> spaceManager.overview(s.spaceId()))
            .toList());
        m.put("users", userManager.listCached().stream()
            .map(u -> Map.of(
                "userId", u.userId(),
                "displayName", u.displayName(),
                "spaceCount", u.spaces().size()
            ))
            .toList());
        return m;
    }

    // ── Assembled Context ──

    /**
     * The fully assembled context for a single message processing turn.
     * This is what the agent receives.
     */
    public record AssembledContext(
        String userId,
        com.nousresearch.hermes.user.UserProfile userProfile,
        com.nousresearch.hermes.space.SpaceContext spaceContext,
        UserManager.MergedCapability mergedCapabilities,
        OrgContext orgContext
    ) {
        /**
         * The effective skill set = user personal skills ∪ space skills,
         * minus user-hidden skills.
         */
        public Set<String> effectiveSkills() {
            return mergedCapabilities.effectiveSkills();
        }

        /**
         * The space policy that governs this interaction.
         */
        public com.nousresearch.hermes.space.SpacePolicy policy() {
            return spaceContext.policy();
        }

        /**
         * The user's preferences (advisory, never overrides policy).
         */
        public com.nousresearch.hermes.user.UserPreferences preferences() {
            return userProfile.preferences();
        }
    }
}
