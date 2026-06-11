package com.nousresearch.hermes;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.config.HermesConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for configuration management.
 */
public class ConfigTest {
    
    @TempDir
    Path tempDir;
    
    @Test
    void testConstants() {
        assertEquals("0.1.0", Constants.VERSION);
        assertNotNull(Constants.OPENROUTER_BASE_URL);
        assertTrue(Constants.DEFAULT_MAX_ITERATIONS > 0);
    }
    
    @Test
    void testHermesHomeSystemPropertyOverride() {
        System.setProperty("hermes.home", tempDir.toString());
        try {
            assertEquals(tempDir, Constants.getHermesHome());
        } finally {
            System.clearProperty("hermes.home");
        }
    }

    @Test
    void testConfigLoad() throws IOException {
        // Set temp directory as HERMES_HOME
        System.setProperty("HERMES_HOME", tempDir.toString());
        try {
            HermesConfig config = HermesConfig.load();
            
            assertNotNull(config);
            assertNotNull(config.getCurrentModel());
            assertTrue(config.getMaxTurns() > 0);
        } finally {
            System.clearProperty("HERMES_HOME");
        }
    }
    
    @Test
    void testConfigSetGet() throws IOException {
        System.setProperty("HERMES_HOME", tempDir.toString());
        try {
            HermesConfig config = HermesConfig.load();
            
            config.setModelOverride("openai:gpt-4");
            assertEquals("openai:gpt-4", config.getCurrentModel());
            
            config.setBaseUrlOverride("https://custom.api.com/v1");
            assertEquals("https://custom.api.com/v1", config.getBaseUrl());
        } finally {
            System.clearProperty("HERMES_HOME");
        }
    }
}
