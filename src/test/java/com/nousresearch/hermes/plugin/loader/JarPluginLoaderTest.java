package com.nousresearch.hermes.plugin.loader;

import com.nousresearch.hermes.plugin.Plugin;
import com.nousresearch.hermes.plugin.context.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for external jar plugin loading.
 *
 * These tests focus on the loader's ability to scan a directory for jars
 * and resolve entry-points. We don't ship a compiled test plugin jar in this
 * repo, so we test the negative paths (empty dir, no entry-point) here.
 * End-to-end jar loading is exercised by integration tests.
 */
class JarPluginLoaderTest {

    @Test
    void testEmptyDirectoryReturnsNull() {
        JarPluginLoader loader = new JarPluginLoader();
        Plugin plugin = loader.loadPlugin(Path.of("/nonexistent/path/that/does/not/exist"));
        assertNull(plugin);
    }

    @Test
    void testDirectoryWithoutJars(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("plugin.yaml"), "name: empty\nkind: standalone\n");
        JarPluginLoader loader = new JarPluginLoader();
        Plugin plugin = loader.loadPlugin(tempDir);
        assertNull(plugin);
    }

    @Test
    void testPluginPropertiesWithoutMainClass(@TempDir Path tempDir) throws IOException {
        // Create a properties file with no main-class — loader should not crash
        Properties props = new Properties();
        props.setProperty("other-key", "value");
        try (var out = Files.newOutputStream(tempDir.resolve("plugin.properties"))) {
            props.store(out, null);
        }
        JarPluginLoader loader = new JarPluginLoader();
        Plugin plugin = loader.loadPlugin(tempDir);
        assertNull(plugin);
    }

    @Test
    void testLoadPluginFromJarWithManifest(@TempDir Path tempDir) throws Exception {
        // Build a minimal jar in-memory containing a Plugin class
        // We can't compile inside the test, so we use an existing Plugin from main classpath.
        // The loader should find it via JAR manifest Plugin-Class.

        // For this test we just create a jar with a Manifest declaring an existing class
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Plugin-Class",
                "com.nousresearch.hermes.plugin.builtin.feishu.FeishuPlatformPlugin");

        Path jarPath = tempDir.resolve("test-plugin.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            // empty jar with just manifest
        }

        JarPluginLoader loader = new JarPluginLoader();
        Plugin plugin = loader.loadPlugin(tempDir);
        assertNotNull(plugin, "Should load plugin via JAR manifest Plugin-Class");
        assertEquals("FeishuPlatformPlugin", plugin.getClass().getSimpleName());
    }

    @Test
    void testLoadPluginFromPropertiesMainClass(@TempDir Path tempDir) throws Exception {
        // Create a properties file pointing to an existing Plugin class
        Properties props = new Properties();
        props.setProperty("main-class",
                "com.nousresearch.hermes.plugin.builtin.telegram.TelegramPlatformPlugin");
        try (var out = Files.newOutputStream(tempDir.resolve("plugin.properties"))) {
            props.store(out, null);
        }

        // Need at least one jar present for loader to engage (empty jar is fine)
        Path jarPath = tempDir.resolve("dummy.jar");
        try (var jarOut = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // empty jar
        }

        JarPluginLoader loader = new JarPluginLoader();
        Plugin plugin = loader.loadPlugin(tempDir);
        assertNotNull(plugin, "Should load plugin via plugin.properties main-class");
        assertEquals("TelegramPlatformPlugin", plugin.getClass().getSimpleName());
    }
}
