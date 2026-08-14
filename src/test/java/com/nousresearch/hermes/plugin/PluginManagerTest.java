package com.nousresearch.hermes.plugin;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.plugin.hook.HookEngine;
import com.nousresearch.hermes.plugin.hook.HookType;
import com.nousresearch.hermes.plugin.model.PluginKind;
import com.nousresearch.hermes.plugin.model.PluginManifest;
import com.nousresearch.hermes.plugin.model.Source;
import com.nousresearch.hermes.plugin.registry.PlatformEntry;
import com.nousresearch.hermes.plugin.registry.PlatformRegistry;
import com.nousresearch.hermes.plugin.registry.ProviderRegistry;
import com.nousresearch.hermes.plugin.scanner.PluginDirectoryScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the plugin discovery and loading system.
 */
class PluginManagerTest {

    @Test
    void testDirectoryScannerFlatLayout(@TempDir Path tempDir) throws Exception {
        // Create a flat plugin structure
        Path pluginDir = tempDir.resolve("test-plugin");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "name: test-plugin\n" +
                "kind: standalone\n" +
                "version: 1.0.0\n" +
                "description: A test plugin\n");

        PluginDirectoryScanner scanner = new PluginDirectoryScanner();
        var manifests = scanner.scan(tempDir, Source.BUNDLED, null);

        assertEquals(1, manifests.size());
        assertEquals("test-plugin", manifests.get(0).getName());
        assertEquals(PluginKind.STANDALONE, manifests.get(0).getKind());
    }

    @Test
    void testDirectoryScannerCategoryLayout(@TempDir Path tempDir) throws Exception {
        // Create a category plugin structure
        Path categoryDir = tempDir.resolve("image_gen").resolve("openai");
        Files.createDirectories(categoryDir);
        Files.writeString(categoryDir.resolve("plugin.yaml"),
                "name: openai\n" +
                "kind: backend\n" +
                "version: 1.0.0\n");

        PluginDirectoryScanner scanner = new PluginDirectoryScanner();
        var manifests = scanner.scan(tempDir, Source.BUNDLED, null);

        assertEquals(1, manifests.size());
        assertEquals("openai", manifests.get(0).getName());
        assertEquals("image_gen/openai", manifests.get(0).getKey());
        assertEquals(PluginKind.BACKEND, manifests.get(0).getKind());
    }

    @Test
    void testPlatformRegistry() {
        PlatformRegistry registry = new PlatformRegistry();
        assertFalse(registry.isRegistered("test"));

        registry.register("test", PlatformEntry.builder("test", "Test")
                .checkFn(() -> true)
                .adapterFactory(cfg -> "adapter")
                .source("builtin")
                .build());

        assertTrue(registry.isRegistered("test"));
        assertEquals(1, registry.listAll().size());
    }


    // Simple NamedProvider implementation for testing
    private interface NamedProvider extends com.nousresearch.hermes.plugin.registry.NamedProvider {
        static NamedProvider of(String name) {
            return () -> name;
        }
    }
}
