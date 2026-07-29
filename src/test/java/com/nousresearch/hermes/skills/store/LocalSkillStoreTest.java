package com.nousresearch.hermes.skills.store;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LocalSkillStore}.
 */
class LocalSkillStoreTest {

    private LocalSkillStore store;

    @BeforeEach
    void setUp() {
        store = new LocalSkillStore();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Registration
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Register a skill and retrieve it")
    void testRegisterAndGet() {
        SkillStore.SkillRegistration reg = new SkillStore.SkillRegistration(
            "my-skill", "A test skill",
            SkillStore.SkillScope.PRIVATE,
            SkillStore.SkillType.CUSTOM,
            SkillStore.SkillConfig.empty()
        );

        String id = store.register("t1", reg);
        assertNotNull(id);

        SkillStore.SkillInfo info = store.get("t1", id);
        assertNotNull(info);
        assertEquals("my-skill", info.name());
        assertEquals("A test skill", info.description());
        assertTrue(info.enabled());
        assertEquals("1.0.0", info.currentVersion());
    }

    @Test
    @DisplayName("Duplicate skill name throws")
    void testDuplicateNameThrows() {
        SkillStore.SkillRegistration reg = new SkillStore.SkillRegistration(
            "dup", "First", null, null, null);
        store.register("t1", reg);

        SkillStore.SkillRegistration reg2 = new SkillStore.SkillRegistration(
            "dup", "Second", null, null, null);
        assertThrows(IllegalStateException.class, () -> store.register("t1", reg2));
    }

    @Test
    @DisplayName("Unregister removes skill")
    void testUnregister() {
        String id = store.register("t1", new SkillStore.SkillRegistration(
            "temp", "Temporary", null, null, null));

        store.unregister("t1", id);
        assertNull(store.get("t1", id));
    }

    @Test
    @DisplayName("Cannot unregister system skill")
    void testCannotUnregisterSystem() {
        String id = store.register("t1", new SkillStore.SkillRegistration(
            "sys", "System skill",
            SkillStore.SkillScope.SYSTEM, null, null));

        assertThrows(IllegalStateException.class, () -> store.unregister("t1", id));
    }

    @Test
    @DisplayName("Enable and disable skill")
    void testEnableDisable() {
        String id = store.register("t1", new SkillStore.SkillRegistration(
            "toggle", "Toggle me", null, null, null));

        assertTrue(store.get("t1", id).enabled());
        store.disable("t1", id);
        assertFalse(store.get("t1", id).enabled());
        store.enable("t1", id);
        assertTrue(store.get("t1", id).enabled());
    }

    // ══════════════════════════════════════════════════════════════════
    //  Discovery
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("List skills by scope")
    void testListByScope() {
        store.register("t1", new SkillStore.SkillRegistration(
            "private1", "Private", SkillStore.SkillScope.PRIVATE, null, null));
        store.register("t1", new SkillStore.SkillRegistration(
            "shared1", "Shared", SkillStore.SkillScope.SHARED, null, null));

        List<SkillStore.SkillInfo> privateSkills = store.list("t1", SkillStore.SkillScope.PRIVATE);
        assertEquals(1, privateSkills.size());
        assertEquals("private1", privateSkills.get(0).name());

        List<SkillStore.SkillInfo> sharedSkills = store.list("t1", SkillStore.SkillScope.SHARED);
        // t1 has its own private skill + shared skill
        assertTrue(sharedSkills.size() >= 1);
    }

    @Test
    @DisplayName("Find by name")
    void testFindByName() {
        store.register("t1", new SkillStore.SkillRegistration(
            "finder", "Find me", null, null, null));

        SkillStore.SkillInfo info = store.findByName("t1", "finder");
        assertNotNull(info);
        assertEquals("finder", info.name());
    }

    @Test
    @DisplayName("Tenant isolation in discovery")
    void testTenantIsolation() {
        store.register("t1", new SkillStore.SkillRegistration(
            "t1-skill", "T1 only", SkillStore.SkillScope.PRIVATE, null, null));
        store.register("t2", new SkillStore.SkillRegistration(
            "t2-skill", "T2 only", SkillStore.SkillScope.PRIVATE, null, null));

        List<SkillStore.SkillInfo> t1Skills = store.list("t1", null);
        assertTrue(t1Skills.stream().anyMatch(s -> s.name().equals("t1-skill")));
        assertFalse(t1Skills.stream().anyMatch(s -> s.name().equals("t2-skill")));
    }

    @Test
    @DisplayName("SHARED skills visible to all tenants")
    void testSharedSkillVisible() {
        store.register("t1", new SkillStore.SkillRegistration(
            "global-tool", "Shared tool",
            SkillStore.SkillScope.SHARED, null, null));

        // t2 should see t1's shared skill
        List<SkillStore.SkillInfo> t2Skills = store.list("t2", SkillStore.SkillScope.SHARED);
        assertTrue(t2Skills.stream().anyMatch(s -> s.name().equals("global-tool")));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Version Management
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Publish new version")
    void testPublishVersion() {
        String id = store.register("t1", new SkillStore.SkillRegistration(
            "versioned", "Versioned skill", null, null, null));

        SkillStore.SkillConfig v2 = new SkillStore.SkillConfig(
            "v2 content", null, null, null);
        store.publishVersion("t1", id, "2.0.0", v2);

        SkillStore.SkillInfo info = store.get("t1", id);
        assertEquals("2.0.0", info.currentVersion());

        List<String> versions = store.listVersions("t1", id);
        assertEquals(2, versions.size());
        assertTrue(versions.contains("1.0.0"));
        assertTrue(versions.contains("2.0.0"));
    }

    @Test
    @DisplayName("Get active version config")
    void testGetActiveVersion() {
        String id = store.register("t1", new SkillStore.SkillRegistration(
            "cfg", "Config skill", null, null,
            new SkillStore.SkillConfig("initial", null, null, null)));

        SkillStore.SkillConfig active = store.getActiveVersion("t1", id);
        assertNotNull(active);
        assertEquals("initial", active.content());

        SkillStore.SkillConfig v2 = new SkillStore.SkillConfig(
            "updated content", null, null, null);
        store.publishVersion("t1", id, "2.0.0", v2);

        active = store.getActiveVersion("t1", id);
        assertEquals("updated content", active.content());
    }

    @Test
    @DisplayName("Rollback to previous version")
    void testRollback() {
        String id = store.register("t1", new SkillStore.SkillRegistration(
            "rollback-test", "Rollback", null, null,
            new SkillStore.SkillConfig("v1", null, null, null)));

        store.publishVersion("t1", id, "2.0.0",
            new SkillStore.SkillConfig("v2", null, null, null));

        assertEquals("2.0.0", store.get("t1", id).currentVersion());

        store.rollback("t1", id, "1.0.0");
        assertEquals("1.0.0", store.get("t1", id).currentVersion());

        SkillStore.SkillConfig active = store.getActiveVersion("t1", id);
        assertEquals("v1", active.content());
    }

    @Test
    @DisplayName("Rollback to non-existent version throws")
    void testRollbackNotFound() {
        String id = store.register("t1", new SkillStore.SkillRegistration(
            "no-rollback", "No rollback", null, null, null));

        assertThrows(IllegalArgumentException.class,
            () -> store.rollback("t1", id, "99.0.0"));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Change Notification
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Subscribe receives change events")
    void testChangeNotification() {
        java.util.List<SkillStore.SkillChangeEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        store.subscribeChanges(events::add);

        String id = store.register("t1", new SkillStore.SkillRegistration(
            "notify", "Notify", null, null, null));

        assertFalse(events.isEmpty());
        SkillStore.SkillChangeEvent first = events.get(0);
        assertEquals("notify", first.skillName());
        assertEquals(SkillStore.ChangeAction.REGISTERED, first.action());

        store.disable("t1", id);
        assertTrue(events.stream().anyMatch(e -> e.action() == SkillStore.ChangeAction.DISABLED));

        store.enable("t1", id);
        assertTrue(events.stream().anyMatch(e -> e.action() == SkillStore.ChangeAction.ENABLED));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Factory
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SkillStoreFactory returns LocalSkillStore by default")
    void testFactoryDefault() {
        SkillStoreFactory.reset();
        SkillStore store = SkillStoreFactory.get();
        assertInstanceOf(LocalSkillStore.class, store);
    }

    @Test
    @DisplayName("SkillStoreFactory accepts override")
    void testFactoryOverride() {
        LocalSkillStore custom = new LocalSkillStore();
        SkillStoreFactory.set(custom);
        assertSame(custom, SkillStoreFactory.get());
        SkillStoreFactory.reset();
    }
}
