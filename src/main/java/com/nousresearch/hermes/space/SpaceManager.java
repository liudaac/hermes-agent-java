package com.nousresearch.hermes.space;

import com.nousresearch.hermes.improvement.ImprovementSignal;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages space contexts - the team-level layer between organization and user.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Create / load / cache {@link SpaceContext} by spaceId</li>
 *   <li>Wrap existing {@link TenantContext} with semantic space API</li>
 *   <li>Apply space-level evolution signals</li>
 *   <li>Provide space overview for admin UI</li>
 * </ul></p>
 */
public class SpaceManager {

    private static final Logger logger = LoggerFactory.getLogger(SpaceManager.class);

    private final TenantManager tenantManager;
    private final ConcurrentHashMap<String, SpaceContext> cache = new ConcurrentHashMap<>();

    public SpaceManager(TenantManager tenantManager) {
        this.tenantManager = tenantManager;
    }

    /**
     * Enter a space as a specific user.
     * Returns the SpaceContext for the space, registering the user as active.
     */
    public SpaceContext enter(String spaceId, String userId) {
        SpaceContext space = load(spaceId);
        space.updateMemberActivity(userId);
        return space;
    }

    /**
     * Load (or create) a SpaceContext for the given spaceId.
     */
    public SpaceContext load(String spaceId) {
        return cache.computeIfAbsent(spaceId, this::loadFromTenant);
    }

    /**
     * Create a new space.
     */
    public SpaceContext create(String spaceId, String spaceName) {
        SpaceContext space = new SpaceContext(spaceId, spaceName, null);
        cache.put(spaceId, space);
        logger.info("Space created: {} ({})", spaceId, spaceName);
        return space;
    }

    /**
     * Apply an improvement signal at the space level.
     * This is the space-level evolution entry point.
     */
    public void evolve(String spaceId, ImprovementSignal signal) {
        SpaceContext space = load(spaceId);
        switch (signal.type()) {
            case REPEAT_PATTERN -> {
                // Multiple users hit the same pattern -> suggest adding as space capability
                String suggestedSkill = signal.metadata().getOrDefault("suggested_skill", "").toString();
                if (!suggestedSkill.isEmpty()) {
                    logger.info("Space {} evolution: suggest installing skill '{}'", spaceId, suggestedSkill);
                    // In production, this would create an improvement proposal
                }
            }
            default -> {
                logger.debug("Space {} evolution signal: {}", spaceId, signal.type());
            }
        }
    }

    /**
     * List all cached spaces (for admin UI).
     */
    public List<SpaceContext> listCached() {
        return new ArrayList<>(cache.values());
    }

    /**
     * Get space overview for dashboard.
     */
    public Map<String, Object> overview(String spaceId) {
        SpaceContext space = load(spaceId);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("spaceId", space.spaceId());
        overview.put("spaceName", space.spaceName());
        overview.put("memberCount", space.listMembers().size());
        overview.put("knowledgeCount", space.listKnowledge().size());
        overview.put("skillCount", space.capabilities().installedSkills().size());
        overview.put("toolCount", space.capabilities().enabledTools().size());
        overview.put("templateCount", space.capabilities().availableTemplates().size());
        return overview;
    }

    private SpaceContext loadFromTenant(String spaceId) {
        // Try to get existing tenant context
        TenantContext tenant = null;
        if (tenantManager != null) {
            try {
                tenant = tenantManager.getTenant(spaceId);
            } catch (Exception e) {
                logger.debug("No tenant found for space {}, operating without tenant backend", spaceId);
            }
        }
        String name = tenant != null ? spaceId : spaceId;  // TODO: get display name from config
        return new SpaceContext(spaceId, name, tenant);
    }
}
