package com.nousresearch.hermes.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConfigManager.
 */
public class ConfigManagerTest {
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() {
        ConfigManager.getInstance(); // Ensure singleton is reset if needed
    }
    
    @Test
    void testDefaultConfigCreation() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        
        ConfigManager config = ConfigManager.getInstance();
        config.load(configPath);
        
        assertTrue(Files.exists(configPath));
        assertEquals("openrouter", config.getModelProvider());
        assertEquals(30, config.getMaxTurns());
    }
    
    @Test
    void testGetString() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        ConfigManager config = ConfigManager.getInstance();
        config.load(configPath);
        
        assertNotNull(config.getString("model.provider"));
        assertEquals("default", config.getString("nonexistent", "default"));
    }
    
    @Test
    void testGetInt() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        ConfigManager config = ConfigManager.getInstance();
        config.load(configPath);
        
        assertTrue(config.getInt("agent.max_turns", 0) > 0);
        assertEquals(42, config.getInt("nonexistent", 42));
    }
    
    @Test
    void testSetAndSave() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        ConfigManager config = ConfigManager.getInstance();
        config.load(configPath);
        
        config.set("test.value", "hello");
        config.save();
        
        // Reload and verify
        config.load(configPath);
        assertEquals("hello", config.getString("test.value"));
    }
}
