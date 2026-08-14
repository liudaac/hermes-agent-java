package com.nousresearch.hermes.user;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.org.OrgManager;
import com.nousresearch.hermes.space.SpaceContext;
import com.nousresearch.hermes.space.SpaceManager;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for the three-layer main line:
 * User -> Space -> Org assembly flow.
 */
class ThreeLayerAssemblyTest {

    @Test
    void shouldAssembleThreeLayerContext() {
        // Given: three-layer infrastructure
        TenantManager tenantManager = new TenantManager();
        tenantManager.initializeDefaultTenant();

        SpaceManager spaceManager = new SpaceManager(tenantManager);
        UserManager userManager = new UserManager(
            com.nousresearch.hermes.auth.UserIdentityResolver.passthrough()
        );

        HermesConfig config = new HermesConfig("test-key", "http://localhost", "test-model");
        OrgManager orgManager = new OrgManager(config, spaceManager, userManager);

        // When: assemble context for a user from a channel
        var assembled = orgManager.assemble("qqbot", "test_user_001", "default");

        // Then: all three layers are resolved
        assertNotNull(assembled);
        assertEquals("test_user_001", assembled.userId());          // passthrough identity
        assertNotNull(assembled.userProfile());                      // user layer
        assertNotNull(assembled.spaceContext());                     // space layer
        assertNotNull(assembled.orgContext());                       // org layer

        // User profile has default preferences
        assertEquals("zh-CN", assembled.preferences().language());
        assertEquals("concise", assembled.preferences().responseStyle());

        // Space context wraps tenant
        assertEquals("default", assembled.spaceContext().spaceId());

        // Merged capabilities starts empty (no skills installed yet)
        assertTrue(assembled.effectiveSkills().isEmpty());
    }

    @Test
    void shouldMergeUserAndSpaceCapabilities() {
        // Given
        TenantManager tenantManager = new TenantManager();
        tenantManager.initializeDefaultTenant();
        SpaceManager spaceManager = new SpaceManager(tenantManager);
        UserManager userManager = new UserManager(
            com.nousresearch.hermes.auth.UserIdentityResolver.passthrough()
        );

        // User has personal skills
        UserProfile profile = userManager.load("test_user_002");
        profile.capabilities().addPersonalSkill("my-personal-skill");
        profile.capabilities().addFrequentTool("terminal");
        userManager.save(profile);

        // Space has shared skills
        SpaceContext space = spaceManager.load("default");
        space.capabilities().installSkill("team-shared-skill");

        // When: merge capabilities
        var merged = userManager.mergeCapabilities(
            profile,
            space.capabilities().installedSkills()
        );

        // Then: effective set = user ∪ space
        assertTrue(merged.effectiveSkills().contains("my-personal-skill"));
        assertTrue(merged.effectiveSkills().contains("team-shared-skill"));
        assertTrue(merged.frequentTools().contains("terminal"));
        assertEquals(2, merged.effectiveSkills().size());
    }

    @Test
    void shouldRespectHiddenCapabilities() {
        TenantManager tenantManager = new TenantManager();
        tenantManager.initializeDefaultTenant();
        SpaceManager spaceManager = new SpaceManager(tenantManager);
        UserManager userManager = new UserManager(
            com.nousresearch.hermes.auth.UserIdentityResolver.passthrough()
        );

        UserProfile profile = userManager.load("test_user_003");
        profile.capabilities().addPersonalSkill("personal-skill");
        profile.capabilities().hide("annoying-team-skill");
        userManager.save(profile);

        SpaceContext space = spaceManager.load("default");
        space.capabilities().installSkill("annoying-team-skill");
        space.capabilities().installSkill("useful-team-skill");

        var merged = userManager.mergeCapabilities(
            profile,
            space.capabilities().installedSkills()
        );

        // Hidden skill is filtered out
        assertFalse(merged.effectiveSkills().contains("annoying-team-skill"));
        // Others remain
        assertTrue(merged.effectiveSkills().contains("personal-skill"));
        assertTrue(merged.effectiveSkills().contains("useful-team-skill"));
    }

    @Test
    void shouldAdaptUserPreferencesFromSignal() {
        TenantManager tenantManager = new TenantManager();
        SpaceManager spaceManager = new SpaceManager(tenantManager);
        UserManager userManager = new UserManager(
            com.nousresearch.hermes.auth.UserIdentityResolver.passthrough()
        );

        // User sends correction signal
        var signal = com.nousresearch.hermes.improvement.ImprovementSignal.create(
            "default", "test_user_004",
            com.nousresearch.hermes.improvement.SignalType.USER_CORRECTION,
            com.nousresearch.hermes.improvement.SignalScope.USER,
            "session-1",
            "User wants formal tone",
            1.0,
            java.util.Map.of("preference_key", "tone", "preference_value", "formal")
        );

        userManager.adapt("test_user_004", signal);

        // Then: preference was adapted
        UserProfile updated = userManager.load("test_user_004");
        assertEquals("formal", updated.preferences().extra().get("tone"));
    }

    @Test
    void shouldManageSpaceKnowledgeLifecycle() {
        TenantManager tenantManager = new TenantManager();
        SpaceManager spaceManager = new SpaceManager(tenantManager);

        SpaceContext space = spaceManager.load("default");

        // Add knowledge
        var entry = new com.nousresearch.hermes.space.KnowledgeEntry(
            "k_test_1", "部署SOP", "1. 拉代码 2. 编译 3. 重启",
            "sop", java.util.List.of("deploy", "ops"), "admin",
            System.currentTimeMillis(), System.currentTimeMillis()
        );
        space.addKnowledge(entry);

        assertEquals(1, space.listKnowledge().size());

        // Search
        var results = space.searchKnowledge("部署");
        assertEquals(1, results.size());
        assertEquals("部署SOP", results.get(0).title());

        // Update
        space.updateKnowledge("k_test_1", "部署SOP v2", "1. 拉代码 2. 编译 3. 测试 4. 重启", "sop");
        var updated = space.getKnowledge("k_test_1");
        assertEquals("部署SOP v2", updated.title());

        // Delete
        assertTrue(space.removeKnowledge("k_test_1"));
        assertEquals(0, space.listKnowledge().size());
    }

    @Test
    void shouldRouteSignalsByScope() {
        TenantManager tenantManager = new TenantManager();
        tenantManager.initializeDefaultTenant();
        SpaceManager spaceManager = new SpaceManager(tenantManager);
        UserManager userManager = new UserManager(
            com.nousresearch.hermes.auth.UserIdentityResolver.passthrough()
        );
        HermesConfig config = new HermesConfig("test-key", "http://localhost", "test-model");
        OrgManager orgManager = new OrgManager(config, spaceManager, userManager);

        // USER scope -> goes to userManager.adapt
        var userSignal = com.nousresearch.hermes.improvement.ImprovementSignal.create(
            "default", "test_user_005",
            com.nousresearch.hermes.improvement.SignalType.EXPLICIT_FEEDBACK,
            com.nousresearch.hermes.improvement.SignalScope.USER,
            "s1", "feedback", 0.8,
            java.util.Map.of("feedback", "prefer concise")
        );
        assertDoesNotThrow(() -> orgManager.routeSignal(userSignal, "test_user_005", "default"));

        // SPACE scope -> goes to spaceManager.evolve
        var spaceSignal = com.nousresearch.hermes.improvement.ImprovementSignal.create(
            "default", null,
            com.nousresearch.hermes.improvement.SignalType.REPEAT_PATTERN,
            com.nousresearch.hermes.improvement.SignalScope.SPACE,
            "s2", "multiple users ask for weather", 0.7,
            java.util.Map.of("suggested_skill", "weather-skill")
        );
        assertDoesNotThrow(() -> orgManager.routeSignal(spaceSignal, null, "default"));

        // ORG scope -> goes to org insight
        var orgSignal = com.nousresearch.hermes.improvement.ImprovementSignal.create(
            "default", null,
            com.nousresearch.hermes.improvement.SignalType.RATING_LOW,
            com.nousresearch.hermes.improvement.SignalScope.ORG,
            "s3", "cross-space low rating pattern", 0.6,
            java.util.Map.of()
        );
        assertDoesNotThrow(() -> orgManager.routeSignal(orgSignal, null, "default"));
    }
}
