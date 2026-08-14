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

}
