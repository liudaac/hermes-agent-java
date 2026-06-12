package com.nousresearch.hermes.skills;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillManagerTest {
    @TempDir
    Path tempDir;

    private final String oldHermesHome = System.getProperty("hermes.home");
    private final String oldSkillPaths = System.getProperty("hermes.skills.paths");

    @AfterEach
    void restoreProperties() {
        restore("hermes.home", oldHermesHome);
        restore("hermes.skills.paths", oldSkillPaths);
    }

    @Test
    void loadsSkillsFromConfiguredExternalSearchPath() throws Exception {
        Path hermesHome = tempDir.resolve("hermes-home");
        Path external = tempDir.resolve("openclaw-skills");
        Path skillDir = external.resolve("kimi-webbridge");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: kimi-webbridge
            description: Kimi WebBridge official skill
            version: v1.9.17
            tags: [browser, webbridge]
            ---

            # Kimi WebBridge
            Use POST /command.
            """);

        System.setProperty("hermes.home", hermesHome.toString());
        System.setProperty("hermes.skills.paths", external.toString());

        SkillManager manager = new SkillManager();
        SkillManager.Skill skill = manager.loadSkill("kimi-webbridge");

        assertNotNull(skill);
        assertEquals("kimi-webbridge", skill.name);
        assertEquals("external", skill.source);
        assertEquals(skillDir.toAbsolutePath().normalize().toString(), skill.path);
        assertEquals(1, skill.version, "non-numeric semver-style versions should not break parsing");
        assertTrue(manager.listSkills().stream().anyMatch(s -> "kimi-webbridge".equals(s.name)));
    }

    @Test
    void primaryHermesSkillOverridesExternalSkill() throws Exception {
        Path hermesHome = tempDir.resolve("hermes-home");
        Path primary = hermesHome.resolve("skills").resolve("alpha");
        Path external = tempDir.resolve("external").resolve("alpha");
        Files.createDirectories(primary);
        Files.createDirectories(external);
        Files.writeString(primary.resolve("SKILL.md"), "Primary skill");
        Files.writeString(external.resolve("SKILL.md"), "External skill");

        System.setProperty("hermes.home", hermesHome.toString());
        System.setProperty("hermes.skills.paths", tempDir.resolve("external").toString());

        SkillManager manager = new SkillManager();
        SkillManager.Skill skill = manager.loadSkill("alpha");

        assertNotNull(skill);
        assertEquals("hermes", skill.source);
        assertEquals("Primary skill", skill.content);
        assertEquals(1, manager.listSkills().stream().filter(s -> "alpha".equals(s.name)).count());
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
